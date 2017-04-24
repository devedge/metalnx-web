/*
 * Copyright (c) 2015-2017, Dell EMC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.emc.metalnx.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;
import javax.servlet.ServletException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.emc.metalnx.core.domain.exceptions.DataGridException;
import com.emc.metalnx.services.interfaces.*;
import com.emc.metalnx.core.domain.exceptions.DataGridConnectionRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.metalnx.services.interfaces.IRODSServices;
import com.emc.metalnx.services.interfaces.UserService;
import com.emc.metalnx.services.auth.UserTokenDetails;

/********** iRODS File transfer imports ***************/
import org.irods.jargon.core.connection.IRODSAccount;
import org.irods.jargon.core.connection.SettableJargonProperties;
import org.irods.jargon.core.exception.JargonException;
import org.irods.jargon.core.exception.OverwriteException;
import org.irods.jargon.core.pub.io.IRODSFile;
import org.irods.jargon.core.pub.IRODSFileSystem;
import org.irods.jargon.core.pub.io.IRODSFileFactory;
import org.irods.jargon.core.pub.DataTransferOperations;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;

// Imports to print full stack traces to the logs
import java.io.StringWriter;
import java.io.PrintWriter;


@Controller
@Scope(WebApplicationContext.SCOPE_SESSION)
@RequestMapping(value = "/rules")
public class RulesController {

    @Autowired
    UserService userService;

    @Autowired
    IRODSServices irodsServices;
	
	@Autowired
    ResourceService resourceService;

    private UserTokenDetails userTokenDetails = (UserTokenDetails) SecurityContextHolder.getContext().getAuthentication().getDetails();
    private IRODSAccount irodsAccount = userTokenDetails.getIrodsAccount();
    private IRODSFileSystem irodsFileSystem = userTokenDetails.getIrodsFileSystem();
    private IRODSFileFactory irodsFileFactory = null;
    private final String TMP_DIR = "/tmp/emc-tmp-rules/"; // local directory for temporarily storing files.
	
	private static final Logger logger = LoggerFactory.getLogger(RulesController.class);

    /**
     * Responds to the rules request
     * Minimal rules controller to handle webpage routing
     *
     * @param model
     * @return the template with the rules page
     */
    @RequestMapping(value = "/")
    public String index(Model model) throws DataGridConnectionRefusedException {
		
		try {
			model.addAttribute("resources", resourceService.findAll()); // TODO: returns all of the servers
			// logger.info("ALL DATA RETURNED FROM RESOURCE SERVICE {}", resourceService.findAll());
			
		} catch (DataGridException e) {
			logger.error("Could not respond to request for collections: {}", e);
            model.addAttribute("unexpectedError", true);
		}
		
        return "rules/rules";
    }

    @RequestMapping(value = "deployNewRule/", method = RequestMethod.POST, produces = {"text/plain"})
    @ResponseStatus(value = HttpStatus.OK)
    public ResponseEntity<?> deployNewRule(Model model, HttpServletRequest request) throws JargonException, IOException, ServletException, JSONException, DataGridConnectionRefusedException {

        irodsFileFactory = irodsServices.getIRODSFileFactory();
        JSONObject jsonUploadMsg = new JSONObject();
        HttpStatus status = HttpStatus.OK;
        File file = null;
        String transmission_error_msg = "ERROR while getting transmission response";

        try {
            MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
            
            // Get the file to transfer
            MultipartFile multipartFile = multipartRequest.getFile("file");
            file = convert(multipartFile);
            logger.info("-------> File received: {}",  multipartFile.getOriginalFilename());

            // Get transfer options
            String overwriteTrue = multipartRequest.getParameter("overwriteTrue");
            String command = multipartRequest.getParameter("command");
            logger.info("-------> overwrite duplicates: {}",  Boolean.toString(overwriteTrue));
            logger.info("-------> command: {}",  command);

            // Transmit the file
            int index = 0;
            logger.info("-------> using IRODSAccount: {}", irodsAccount.toString());

            String respFileName = transmit(file, command, index, irodsAccount, overwriteTrue);
            if ( respFileName.equals("")) {
                throw new Exception(transmission_error_msg); 
            }
            
            BufferedReader br = new BufferedReader(new FileReader(new File(respFileName)));
            String transmit_response = br.readLine();
            logger.info("-------> TRANSMISSION RESPONSE: {}", transmit_response);
            br.close();            
            
            jsonUploadMsg.put("filename", multipartFile.getOriginalFilename());
            jsonUploadMsg.put("transmitResponse", transmit_response);
            jsonUploadMsg.put("httpstatus", "OK");

        } catch (JSONException e) {
            logger.info("-------> Error with json response.");
            logger.error("Could not create JSON object for upload response: {]", e.getMessage());

            jsonUploadMsg.put("serverError", e.getMessage());
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        } catch (OverwriteException e) {
            logger.info("File " + file.getName() + " already exists on target system. Must be removed using \'irm\' on target.");

            jsonUploadMsg.put("serverError", e.getMessage());
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        } catch (Exception e) {
            logger.info("-------> Unknown exception in deployNewRule()");
			logStackTrace("-------> Exception Stack Trace: {}", e);
			
            jsonUploadMsg.put("serverError", e.getMessage());
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        if (!(request instanceof MultipartHttpServletRequest)) {
            logger.info("-------> (DEBUG): Request is not multipart request.");
            logger.debug("Request is not a multipart request.");
        }

        return new ResponseEntity<>(jsonUploadMsg.toString(),  status);
    }

    /*
     * **************************************************************************
     * **************************** PRIVATE METHODS *****************************
     * **************************************************************************
     */
    private final String IRODS_PATH = "/tempZone/home/rods/.rulecache/";
    
    /**
     * Takes a multipart file (httpservlet) and converts it to a Java IO File.
     * @param MultipartFile file    the file received from ajax call
     * @return File convFile        the converted file
     * @throws IOException
     * @throws FileNotFoundException
     */
    private File convert(MultipartFile file) throws IOException, FileNotFoundException {
        boolean success = (new File(TMP_DIR)).mkdirs();
        File convFile = new File(TMP_DIR + file.getOriginalFilename());
        convFile.createNewFile(); 
        FileOutputStream fos = new FileOutputStream(convFile); 
        fos.write(file.getBytes());
        fos.close(); 
        return convFile;
    }

    /**
     * Log the message and stack trace for an error.
     * @param String s         The formatted string to print, should include {}.
     * @param Exception e      The exception whose message and stack trace should be printed.
     */
    private void logStackTrace( String s, Exception e ) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        String excToString = sw.toString();
        logger.info("-------> Exception Message: {}", e.getMessage());
        logger.info(s, excToString);
    }

    /**
     * Transmits a command to an iRODS host.
     * @param file          The input file
     * @param command       [deploy, delete]
     * @param index         based on number of servers on the grid
     * @param host          the name of the host you are transmitting to
     * @param user          the user who is currently transmitting a file
     * @param password      the password user trasmitting a file
     * @return String       a timestamp of the transmission
     * @throws JargonException
     */
    private String transmit(File file, String command, int index, IRODSAccount irodsAccount, boolean overwriteTrue) throws JargonException, DataGridConnectionRefusedException {
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        File newFile = null;
        String resp = "";
        
        try {
            newFile = prepareFile(command, index, timestamp, file, overwriteTrue);
            putFile(newFile, irodsAccount);
            resp = getResponse(newFile.getName(), irodsAccount);
            newFile.delete();
        } catch (OverwriteException e) {
            logStackTrace("-------> OverwriteException in transmit(). File already exists on target: {}", e);
        } catch (IOException e) {
            logStackTrace("-------> IOException in transmit(): {}", e);
        } catch (Exception e) {
            logStackTrace("-------> Exception in transmit(): {}", e);
        }
        newFile.delete();
        
        return resp;
    }


    private File prepareFile(String command, int index, String timestamp, File file, boolean overwriteTrue) throws FileNotFoundException, IOException {
        logger.info("-------> Appending local file with command: {}", command);

        String indexString = Integer.toString(index);
        String newfileName = indexString + "_" + timestamp + "-" + file.getName();
        
        // open streams
        OutputStreamWriter os = new OutputStreamWriter(new FileOutputStream(new File(TMP_DIR + newfileName)));
        BufferedReader br = new BufferedReader(new FileReader(file));
        
        // create new file, prepended with command
        os.write(command + ",");
        if (overwriteTrue) {
            os.write("overwrite=yes\n");
        } else {
            os.write("overwrite=no\n");
        }

        String line;
        while ((line = br.readLine()) != null) {
            os.write(line + "\n");
        }
        
        // close streams
        os.close();
        br.close();
        
        return new File(TMP_DIR + newfileName);
    }

    private int putFile(File localFile, IRODSAccount irodsAccount) throws JargonException, DataGridConnectionRefusedException {
        logger.info("-------> Putting local file onto server: {}", localFile.getName());
        
        // authorize with iRODS
        IRODSFile iRODSFile = irodsFileFactory.instanceIRODSFile(IRODS_PATH + localFile.getName());
        DataTransferOperations dataTransferOperationsAO = irodsFileSystem.getIRODSAccessObjectFactory().getDataTransferOperations(irodsAccount);

        // transfer file to iRODS grid
        dataTransferOperationsAO.putOperation(localFile, iRODSFile, null, null);
        
        return 0;
    }

    private String getResponse(String filename, IRODSAccount irodsAccount) throws JargonException, DataGridConnectionRefusedException {
        logger.info("-------> Getting response file back from sever: {}", filename + ".res");
        
        // authorize with iRODS
        DataTransferOperations dataTransferOperationsAO = irodsFileSystem.getIRODSAccessObjectFactory().getDataTransferOperations(irodsAccount);
        
        // generate the files:
        // localFile
        String local_filename = TMP_DIR + "output_" + filename + ".res";
        File localFile = new File(local_filename);
        if (localFile.exists()) {
            localFile.delete();
        }

        // iRODS file
        String iRODSFilename = filename + ".res";
        IRODSFile iRODSFile = irodsFileFactory.instanceIRODSFile(IRODS_PATH + iRODSFilename);
        
        // get operation - retry until success. checks once per second
        boolean done = false;
        int loop_run = 0;
        final int LOOP_LIMIT = 3;
        while (!done) {
            try {
                dataTransferOperationsAO.getOperation(iRODSFile, localFile, null, null);
                done = true;
            } catch (JargonException e) {
                if (!e.getMessage().equals("File not found")) {
                    throw e;
                }
                
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    logStackTrace("-------> InterruptedException in getResponse(): {}", e);
                };
            }

            loop_run++;
            logger.info("-------> GetOperation loop #: {}", loop_run);

            if ( loop_run >= LOOP_LIMIT ) {
                logger.info("-------> Number of GetOperation loops has exceeded {}. Canceling GetOperation.", LOOP_LIMIT);
                return "";
            }
        }
        
        return local_filename;
    }


}

