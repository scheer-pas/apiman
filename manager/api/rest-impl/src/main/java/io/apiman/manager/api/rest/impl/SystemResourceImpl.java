/*
 * Copyright 2014 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apiman.manager.api.rest.impl;

import io.apiman.common.logging.ApimanLoggerFactory;
import io.apiman.common.logging.IApimanLogger;
import io.apiman.common.util.MediaType;
import io.apiman.manager.api.beans.download.DownloadBean;
import io.apiman.manager.api.beans.download.DownloadType;
import io.apiman.manager.api.beans.system.SystemStatusBean;
import io.apiman.manager.api.config.Version;
import io.apiman.manager.api.core.IDownloadManager;
import io.apiman.manager.api.core.IStorage;
import io.apiman.manager.api.core.exceptions.StorageException;
import io.apiman.manager.api.exportimport.json.JsonExportWriter;
import io.apiman.manager.api.exportimport.manager.StorageExporter;
import io.apiman.manager.api.exportimport.write.IExportWriter;
import io.apiman.manager.api.rest.ISystemResource;
import io.apiman.manager.api.rest.exceptions.NotAuthorizedException;
import io.apiman.manager.api.rest.exceptions.SystemErrorException;
import io.apiman.manager.api.security.ISecurityContext;
import io.apiman.manager.api.service.ImportExportService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.MessageFormat;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;

/**
 * Implementation of the System API.
 *
 * @author eric.wittmann@redhat.com
 */
@ApplicationScoped
@Transactional
public class SystemResourceImpl implements ISystemResource {
    
    private IStorage storage;
    private ISecurityContext securityContext;
    private Version version;
    private StorageExporter exporter;
    private IDownloadManager downloadManager;
    private ImportExportService importExportService;

    @Context
    private HttpServletRequest request;

    /**
     * Constructor.
     */
    @Inject
    public SystemResourceImpl(
         IStorage storage,
         ISecurityContext securityContext,
         Version version,
         StorageExporter exporter,
         IDownloadManager downloadManager,
         ImportExportService importExportService
    ) {
        this.storage = storage;
        this.securityContext = securityContext;
        this.version = version;
        this.exporter = exporter;
        this.downloadManager = downloadManager;
        this.importExportService = importExportService;
    }

    public SystemResourceImpl() {
    }

    /**
     * @see ISystemResource#getStatus()
     */
    @Override
    public SystemStatusBean getStatus() {
        SystemStatusBean rval = new SystemStatusBean();
        rval.setId("apiman-manager-api"); //$NON-NLS-1$
        rval.setName("API Manager REST API"); //$NON-NLS-1$
        rval.setDescription("The API Manager REST API is used by the API Manager UI to get stuff done. You can use it to automate any API Management task you wish. For example, create new Organizations, Plans, Clients, and APIs."); //$NON-NLS-1$
        rval.setMoreInfo("https://www.apiman.io/latest/api-manager-restdocs.html"); //$NON-NLS-1$
        rval.setUp(storage != null);
        if (version != null) {
            rval.setVersion(version.getVersionString());
            rval.setBuiltOn(version.getVersionDate());
        }
        return rval;
    }

    /**
     * @see ISystemResource#exportData(java.lang.String)
     */
    @Override
    public Response exportData(String download) throws NotAuthorizedException {
        securityContext.checkAdminPermissions();

        if (BooleanUtils.toBoolean(download)) {
            try {
                DownloadBean dbean = downloadManager.createDownload(DownloadType.exportJson, "/system/export"); //$NON-NLS-1$
                return Response.ok(dbean, MediaType.APPLICATION_JSON).build();
            } catch (StorageException e) {
                throw new SystemErrorException(e);
            }
        } else {
            return exportData();
        }
    }

    /**
     * @see ISystemResource#exportData()
     */
    @Override
    public Response exportData() {
        final IApimanLogger exportLogger = ApimanLoggerFactory.getLogger(IExportWriter.class);
        StreamingOutput stream = new StreamingOutput() {
            @Override
            public void write(OutputStream os) throws IOException, WebApplicationException {
                IExportWriter writer = new JsonExportWriter(os, exportLogger);
                exporter.init(writer);
                exporter.export();
                os.flush();
            }
        };
        return Response
                .ok(stream, MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=api-manager-export.json") //$NON-NLS-1$ //$NON-NLS-2$
                .build();
    }

    /**
     * @see ISystemResource#importData()
     */
    @Override
    public Response importData() throws NotAuthorizedException {
        securityContext.checkAdminPermissions();

        // First, stream the import data to a temporary file.  We do this so
        // that we can stream the import logging statements back to the HTTP
        // response.  We can't stream the inbound data into the importer
        // *and* stream the importer's logging output back to the HTTP
        // response at the same time due to the nature of HTTP.
        File tempFile;
        InputStream data;
        try {
            tempFile = File.createTempFile("apiman_import", ".json"); //$NON-NLS-1$ //$NON-NLS-2$
            tempFile.deleteOnExit();
            data = request.getInputStream();
            FileUtils.copyInputStreamToFile(data, tempFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        final File importFile = tempFile;

        // Next, do the import and stream the import logging output back to
        // the HTTP response output stream.
        StreamingOutput stream = new StreamingOutput() {
            @Override
            @Transactional
            public void write(final OutputStream output) throws IOException, WebApplicationException {
                final PrintWriter writer = new PrintWriter(output);
                IApimanLogger logger = new IApimanLogger() {
                    @Override
                    public void warn(String message) {
                        writer.println("WARN: " + message); //$NON-NLS-1$
                        writer.flush();
                    }

                    @Override
                    public void warn(String message, Object... args) {
                        warn(MessageFormat.format(message, args));
                    }

                    @Override
                    public void trace(String message) {
                        writer.println("TRACE: " + message); //$NON-NLS-1$
                        writer.flush();
                    }

                    @Override
                    public void trace(String message, Object... args) {
                        trace(MessageFormat.format(message, args));
                    }

                    @Override
                    public void info(String message) {
                        writer.println("INFO: " + message); //$NON-NLS-1$
                        writer.flush();
                    }

                    @Override
                    public void info(String message, Object... args) {
                        info(MessageFormat.format(message, args));
                    }

                    @Override
                    public void error(String message, Throwable error) {
                        writer.println("ERROR: " + message); //$NON-NLS-1$
                        error.printStackTrace(writer);
                        writer.flush();
                    }

                    @Override
                    public void error(Throwable error) {
                        writer.println("ERROR: " + error.getMessage()); //$NON-NLS-1$
                        error.printStackTrace(writer);
                        writer.flush();
                    }

                    @Override
                    public void error(Throwable error, String message, Object... args) {
                        error(MessageFormat.format(message, args), error);
                    }

                    @Override
                    public void debug(String message) {
                        writer.println("DEBUG: " + message); //$NON-NLS-1$
                        writer.flush();
                    }

                    @Override
                    public void debug(String message, Object... args) {
                        debug(MessageFormat.format(message, args));
                    }
                };
                // Make sure we call through a managed service as the other `Stream` takes us out the managed context
                // which can mess with our CDI interceptors.
                importExportService.fullImport(importFile, logger);
            }
        };

        return Response.ok(stream).build();
    }
}
