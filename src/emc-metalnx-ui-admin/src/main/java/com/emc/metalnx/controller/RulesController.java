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

import com.emc.metalnx.core.domain.exceptions.DataGridException;
import com.emc.metalnx.services.interfaces.*;
import com.emc.metalnx.core.domain.exceptions.DataGridConnectionRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.metalnx.services.interfaces.IRODSServices;
import com.emc.metalnx.services.interfaces.UserService;

/********** iRODS File transfer imports ***************/
import org.irods.jargon.core.pub.DataTransferOperations;
import org.irods.jargon.core.pub.IRODSFileSystem;

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

import org.irods.jargon.core.connection.IRODSAccount;
import org.irods.jargon.core.connection.SettableJargonProperties;
import org.irods.jargon.core.exception.JargonException;
import org.irods.jargon.core.exception.OverwriteException;
import org.irods.jargon.core.pub.io.IRODSFile;
import org.irods.jargon.core.pub.io.IRODSFileFactory;

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
	
	
	private static final Logger logger = LoggerFactory.getLogger(CollectionController.class);

    /**
     * Responds to the rules request
     * Minimal rules controller to handle webpage routing
     *
     * @param model
     * @return the template with the rules page
     */
    @RequestMapping(value = "/")
    public String listrules(Model model) throws DataGridConnectionRefusedException {
		
		try {
			model.addAttribute("resources", resourceService.findAll());
			// logger.info("ALL DATA RETURNED FROM RESOURCE SERVICE {}", resourceService.findAll());
			
		} catch (DataGridException e) {
			logger.error("Could not respond to request for collections: {}", e);
            model.addAttribute("unexpectedError", true);
		}
		
        return "rules/rules";
    }


    /**
     * 
     * 
     * @param filename      The name of the file to be deployed.
     * @param command       Should be "deploy" when deploying a rule.
     * @param index         The index of the file within the batch
     * @param host          The hostname of the iRODS server we are deploying to.
     * @param user          The admin user who is deploying a rule.
     * @param password      The password of the admin user who is deploying a rule.
     * @return treeView template that renders all nodes of certain path (parent)
     * @throws DataGridConnectionRefusedException
     */
    @RequestMapping(value = "deployNewRule/", method = RequestMethod.POST) // TODO: may need to make it /deployNewRule/ with preceding slash.
    public String deployNewRule(Model model, HttpServletRequest request) throws JargonException, IOException, ServletException { // TODO: Code from iRODSPutFileTest.uploadWithFileVerification()
        Part filePart = request.getPart("file");
        String fileName = String.valueOf("fileName");
        File file = new File(fileName);
        logger.info("---------------------> File received: {}", fileName);

        // String command = "deploy";
        // int index = 0;
        // String host = "sd-vm14.csc.ncsu.edu";
        // String user = "rods";
        // String password = "irods";

        // // for (server : server_list) { trasmit(); }
        // int response = transmit(file, command, index, host, user, password);
        // logger.info("Put file response: {}", response);

        return "rules/rules";
    }

    /*
     * **************************************************************************
     * **************************** PRIVATE METHODS *****************************
     * **************************************************************************
     */

    private static IRODSFileSystem irodsFileSystem = null;

    final int PORT = 1247;
    final String HOME_DIR = "/tempZone/home/rods";
    final String ZONE = "tempZone";
    final String RESOURCE = "demoResc";
    
    // path for iRODS grid
    String IRODS_PATH = "/tempZone/home/rods/.rulecache/";
    
    // print streams
    PrintStream originalOut;
    PrintStream originalErr;

    /* IMPORT:

    * transmit
    * preparefile
    * putfile
    * getresponse
    */
}

