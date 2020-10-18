/*

Copyright 2011, Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,           
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY           
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

package org.openrefine.importers;

import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.hadoop.fs.FileSystem;
import org.openrefine.ProjectMetadata;
import org.openrefine.importers.ImporterUtilities.MultiFileReadingProgress;
import org.openrefine.importing.ImportingFileRecord;
import org.openrefine.importing.ImportingJob;
import org.openrefine.importing.ImportingParser;
import org.openrefine.model.DatamodelRunner;
import org.openrefine.model.GridState;
import org.openrefine.util.JSONUtilities;
import org.openrefine.util.ParsingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Importer which handles the import of multiple files as a single one.
 */
abstract public class ImportingParserBase implements ImportingParser {
    final static Logger logger = LoggerFactory.getLogger("ImportingParserBase");

    final protected Mode mode;
    final protected DatamodelRunner runner;
    
    /**
     * Determines how a file should be read by the subclass, which
     * implements the corresponding method accordingly.
     */
    public static enum Mode {
    	InputStream,
    	Reader,
    	SparkURI
    };
    
    /**
     * @param mode true if parser takes an InputStream, false if it takes a Reader.
     */
    protected ImportingParserBase(Mode mode, DatamodelRunner runner) {
        this.mode = mode;
        this.runner = runner;
    }
    
    @Override
    public ObjectNode createParserUIInitializationData(ImportingJob job,
            List<ImportingFileRecord> fileRecords, String format) {
        ObjectNode options = ParsingUtilities.mapper.createObjectNode();
        JSONUtilities.safePut(options, "includeFileSources", fileRecords.size() > 1);
        
        return options;
    }
    
    @Override
    public GridState parse(ProjectMetadata metadata,
            final ImportingJob job, List<ImportingFileRecord> fileRecords, String format,
            long limit, ObjectNode options) throws Exception {
    	FileSystem hdfs = runner.getFileSystem();
        MultiFileReadingProgress progress = ImporterUtilities.createMultiFileReadingProgress(job, fileRecords, hdfs);
        List<GridState> gridStates = new ArrayList<>(fileRecords.size());
        
        if (fileRecords.isEmpty()) {
        	throw new IllegalArgumentException("No file provided");
        }
        
        long totalRows = 0;
        for (ImportingFileRecord fileRecord : fileRecords) {
            if (job.canceled) {
                break;
            }
            
            long fileLimit = limit < 0 ? limit : Math.max(limit-totalRows,1L);
            GridState gridState = parseOneFile(metadata, job, fileRecord, fileLimit, options, progress);
			gridStates.add(gridState);
            totalRows += gridState.rowCount();

            if (limit > 0 && totalRows >= limit) {
                break;
            }
        }
        return mergeGridStates(gridStates);
    }
    
    /**
     * Merges grids of individual files into one single grid.
     * 
     * @param gridStates
     *    a list of grids returned by the importers
     * @return
     */
    private GridState mergeGridStates(List<GridState> gridStates) {
        if (gridStates.isEmpty()) {
            throw new IllegalArgumentException("No grid states provided");
        }
        GridState current = gridStates.get(0);
        for (int i = 1; i != gridStates.size(); i++) {
            current = ImporterUtilities.mergeGridStates(current, gridStates.get(i));
        }
        return current;
	}

	public GridState parseOneFile(
        ProjectMetadata metadata,
        ImportingJob job,
        ImportingFileRecord fileRecord,
        long limit,
        ObjectNode options,
        final MultiFileReadingProgress progress
    ) throws Exception {
        
        final String fileSource = fileRecord.getFileSource();
        
        progress.startFile(fileSource);
        pushImportingOptions(metadata, fileSource, options);
       
    	if (mode.equals(Mode.SparkURI)) {
    		return parseOneFile(metadata, job, fileSource, fileRecord.getDerivedSparkURI(job.getRawDataDir()), limit, options);
    	} else {
    		final File file = fileRecord.getFile(job.getRawDataDir());
    		try {
	            InputStream inputStream = ImporterUtilities.openAndTrackFile(fileSource, file, progress);
	            try {
	                if (mode.equals(Mode.InputStream)) {
	                    return parseOneFile(metadata, job, fileSource, inputStream, limit, options);
	                } else {
	                    String commonEncoding = JSONUtilities.getString(options, "encoding", null);
	                    if (commonEncoding != null && commonEncoding.isEmpty()) {
	                        commonEncoding = null;
	                    }
	                    
	                    Reader reader = ImporterUtilities.getReaderFromStream(
	                        inputStream, fileRecord, commonEncoding);
	                    
	                    return parseOneFile(metadata, job, fileSource, reader, limit, options);
	                }
	            } finally {
	                inputStream.close();
	            }
    		} finally {
    	        progress.endFile(fileSource, file.length());
	        }
    	}
        
    }
    
	
	/**
	 * Parses one file, designated by a URI understood by Spark.
	 * 
	 * @param metadata
	 *    the project metadata associated with the project to parse (which can be
	 *    modified by the importer)
	 * @param job
	 *    the importing job where this import is being done
	 * @param fileSource
	 *    the original path or source of the file (could be "clipboard" or a URL as well)
	 * @param uri
	 *    the uri understood by Spark where to read the data from
	 * @param limit
	 *    the maximum number of rows to read
	 * @param options
	 *    any options passed to the importer as a JSON payload
	 * @return
	 *    a parsed GridState
	 */
	public GridState parseOneFile(ProjectMetadata metadata, ImportingJob job, String fileSource, String uri,
			long limit, ObjectNode options) throws Exception {
		throw new NotImplementedException("Importer does not support reading from a Spark URI");
	}

	/**
	 * Parses one file, read from a {@class Reader} object,
	 * into a GridState.
	 * 
	 * @param metadata
	 *    the project metadata associated with the project to parse (which can be
	 *    modified by the importer)
	 * @param job
	 *    the importing job where this import is being done
	 * @param fileSource
	 *    the path or source of the file (could be "clipboard" or a URL as well)
	 * @param reader
	 *    the reader object where to read the data from
	 * @param limit
	 *    the maximum number of rows to read
	 * @param options
	 *    any options passed to the importer as a JSON payload
	 * @return
	 *    a parsed GridState
	 * @throws Exception
	 */
    public GridState parseOneFile(
	        ProjectMetadata metadata,
	        ImportingJob job,
	        String fileSource,
	        Reader reader,
	        long limit,
	        ObjectNode options
	    ) throws Exception {
    	throw new NotImplementedException("Importer does not support reading from a Reader");
    }
    
    /**
     * Parses one file, read from an {@class InputStream} object,
     * into a GridState.
     * 
	 * @param metadata
	 *    the project metadata associated with the project to parse (which can be
	 *    modified by the importer)
	 * @param job
	 *    the importing job where this import is being done
	 * @param fileSource
	 *    the path or source of the file (could be "clipboard" or a URL as well)
	 * @param inputStream
	 *    the input stream where to read the data from
	 * @param limit
	 *    the maximum number of rows to read
	 * @param options
	 *    any options passed to the importer as a JSON payload
	 * @return
	 *    a parsed GridState
     * @throws Exception
     */
    public GridState parseOneFile(
            ProjectMetadata metadata,
            ImportingJob job,
            String fileSource,
            InputStream inputStream,
            long limit,
            ObjectNode options
        ) throws Exception {
    	throw new NotImplementedException("Importer does not support reading from an InputStream");
    }


    private void pushImportingOptions(ProjectMetadata metadata, String fileSource, ObjectNode options) {
        options.put("fileSource", fileSource);
        // set the import options to metadata:
        metadata.appendImportOptionMetadata(options);
    }
   
}
