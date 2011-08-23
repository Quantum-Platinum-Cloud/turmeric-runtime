/*******************************************************************************
 * Copyright (c) 2006-2010 eBay Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *******************************************************************************/

package org.ebayopensource.turmeric.runtime.error.cassandra.handler;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

import me.prettyprint.hector.api.exceptions.HectorException;
import org.ebayopensource.turmeric.common.v1.types.CommonErrorData;
import org.ebayopensource.turmeric.common.v1.types.ErrorData;
import org.ebayopensource.turmeric.runtime.common.exceptions.ServiceException;
import org.ebayopensource.turmeric.runtime.common.exceptions.ServiceExceptionInterface;
import org.ebayopensource.turmeric.runtime.common.pipeline.LoggingHandler;
import org.ebayopensource.turmeric.runtime.common.pipeline.LoggingHandlerStage;
import org.ebayopensource.turmeric.runtime.common.pipeline.MessageContext;
import org.ebayopensource.turmeric.runtime.common.types.SOAConstants;
import org.ebayopensource.turmeric.runtime.common.types.SOAHeaders;
import org.ebayopensource.turmeric.runtime.error.cassandra.dao.ErrorCountsDAO;
import org.ebayopensource.turmeric.runtime.error.cassandra.dao.ErrorDAO;
import org.ebayopensource.turmeric.runtime.error.cassandra.dao.ErrorValueDAO;
import org.ebayopensource.turmeric.runtime.error.cassandra.model.Error;
import org.ebayopensource.turmeric.runtime.error.cassandra.model.*;

/**
 * The Class CassandraErrorLoggingHandler.
 *
 * @author manuelchinea
 */
public class CassandraErrorLoggingHandler implements LoggingHandler {

    /** The error dao. */
    private ErrorDAO errorDao = null;
    
    /** The error value dao. */
    private ErrorValueDAO errorValueDao = null;
    
    /** The error counts dao. */
    private ErrorCountsDAO errorCountsDao = null;
    
    /** The cluster name. */
    private String clusterName;
    
    /** The host address. */
    private String hostAddress;
    
    /** The keyspace name. */
    private String keyspaceName;

    /**
     * Instantiates a new cassandra error logging handler.
     */
    public CassandraErrorLoggingHandler() {

    }

    /* (non-Javadoc)
     * @see org.ebayopensource.turmeric.runtime.common.pipeline.LoggingHandler#init(org.ebayopensource.turmeric.runtime.common.pipeline.LoggingHandler.InitContext)
     */
    public void init(InitContext ctx) throws ServiceException {
        java.util.Map<String, String> options = ctx.getOptions();
        clusterName = options.get("cluster-name");
        hostAddress = options.get("host-address");
        keyspaceName = options.get("keyspace-name");
        errorDao = new ErrorDAO(clusterName, hostAddress, keyspaceName, Long.class,
                        org.ebayopensource.turmeric.runtime.error.cassandra.model.Error.class, "Errors");
        errorValueDao = new ErrorValueDAO(clusterName, hostAddress, keyspaceName, String.class,
                        org.ebayopensource.turmeric.runtime.error.cassandra.model.ErrorValue.class, "ErrorValues");
        errorCountsDao = new ErrorCountsDAO(clusterName, hostAddress, keyspaceName);
    }

    /* (non-Javadoc)
     * @see org.ebayopensource.turmeric.runtime.common.pipeline.LoggingHandler#logProcessingStage(org.ebayopensource.turmeric.runtime.common.pipeline.MessageContext, org.ebayopensource.turmeric.runtime.common.pipeline.LoggingHandlerStage)
     */
    public void logProcessingStage(MessageContext ctx, LoggingHandlerStage stage) throws ServiceException {
    }

    /* (non-Javadoc)
     * @see org.ebayopensource.turmeric.runtime.common.pipeline.LoggingHandler#logResponseResidentError(org.ebayopensource.turmeric.runtime.common.pipeline.MessageContext, org.ebayopensource.turmeric.common.v1.types.ErrorData)
     */
    public void logResponseResidentError(MessageContext ctx, ErrorData errorData) throws ServiceException {
        List<? extends ErrorData> errorsToStore = Collections.singletonList(errorData);
        String consumerName = retrieveConsumerName(ctx);
        String serviceAdminName = ctx.getAdminName();
        String operationName = ctx.getOperationName();
        boolean serverSide = !ctx.getServiceId().isClientSide();
        String serverName = getInetAddress().getHostAddress();
        long now = System.currentTimeMillis();
        
        persistErrors(errorsToStore, serverName, serviceAdminName, operationName, serverSide, consumerName, now);

    }

    /* (non-Javadoc)
     * @see org.ebayopensource.turmeric.runtime.common.pipeline.LoggingHandler#logError(org.ebayopensource.turmeric.runtime.common.pipeline.MessageContext, java.lang.Throwable)
     */
    public void logError(MessageContext ctx, Throwable e) throws ServiceException {
        ServiceExceptionInterface serviceException = (ServiceExceptionInterface) e;
        List<CommonErrorData> errorsToStore = serviceException.getErrorMessage().getError();
        String consumerName = retrieveConsumerName(ctx);
        String serviceAdminName = ctx.getAdminName();
        String operationName = ctx.getOperationName();
        boolean serverSide = !ctx.getServiceId().isClientSide();
        String serverName = getInetAddress().getHostAddress();
        long now = System.currentTimeMillis();
        persistErrors(errorsToStore, serverName, serviceAdminName, operationName, serverSide, consumerName, now);

    }

    /**
     * Persist errors.
     *
     * @param errorsToStore the errors to store
     * @param serverName the server name
     * @param srvcAdminName the srvc admin name
     * @param opName the op name
     * @param serverSide the server side
     * @param consumerName the consumer name
     * @param timeStamp the time stamp
     * @throws ServiceException the service exception
     */
    public void persistErrors(List<? extends ErrorData> errorsToStore, String serverName, String srvcAdminName,
                    String opName, boolean serverSide, String consumerName, long timeStamp) throws ServiceException {
        try {

            for (ErrorData errorData : errorsToStore) {
                CommonErrorData commonErrorData = (CommonErrorData) errorData;
                String errorValueKey = commonErrorData.getErrorId() + "-" + serverName + "-" + srvcAdminName + "-"
                                + opName;
                String errorMessage = commonErrorData.getMessage();
                org.ebayopensource.turmeric.runtime.error.cassandra.model.Error errorToSave = new Error();
                errorToSave.setErrorId(commonErrorData.getErrorId());
                errorToSave.setName(commonErrorData.getErrorName());
                errorToSave.setCategory(commonErrorData.getCategory().toString());
                errorToSave.setSeverity(commonErrorData.getSeverity().toString());
                errorToSave.setDomain(commonErrorData.getDomain());
                errorToSave.setSubDomain(commonErrorData.getSubdomain());
                errorToSave.setOrganization(commonErrorData.getOrganization());
                errorDao.save(errorToSave.getErrorId(), errorToSave);

                ErrorValue errorValue = new ErrorValue();
                errorValue.setAggregationPeriod(0);
                errorValue.setConsumerName(consumerName);
                errorValue.setErrorId(errorToSave.getErrorId());
                errorValue.setErrorMessage(errorMessage);
                errorValue.setOperationName(opName);
                errorValue.setServerSide(serverSide);
                errorValue.setServiceAdminName(srvcAdminName);
                errorValue.setServerName(serverName);
                errorValue.setTimeStamp(timeStamp);
                // TODO: not good. What if 2 different error values for the same error occurs at the same time?
                errorValueDao.save(errorValueKey, errorValue);
                errorCountsDao.saveErrorCounts(errorToSave, errorValue, errorValueKey, timeStamp, 1);
            }

        }
        catch (HectorException he) {
            he.printStackTrace();
            throw new ServiceException("Exception logging error in Cassandra cluster", he);
        }
    }

    /* (non-Javadoc)
     * @see org.ebayopensource.turmeric.runtime.common.pipeline.LoggingHandler#logWarning(org.ebayopensource.turmeric.runtime.common.pipeline.MessageContext, java.lang.Throwable)
     */
    public void logWarning(MessageContext ctx, Throwable e) throws ServiceException {
        this.logError(ctx, e);
    }

    /**
     * Retrieve consumer name.
     *
     * @param ctx the ctx
     * @return the string
     * @throws ServiceException the service exception
     */
    private String retrieveConsumerName(MessageContext ctx) throws ServiceException {
        String result = ctx.getRequestMessage().getTransportHeader(SOAHeaders.USECASE_NAME);
        if (result == null)
            result = SOAConstants.DEFAULT_USE_CASE;
        return result;
    }

    /**
     * Gets the inet address.
     *
     * @return the inet address
     * @throws ServiceException the service exception
     */
    private InetAddress getInetAddress() throws ServiceException {
        try {
            return InetAddress.getLocalHost();
        }
        catch (UnknownHostException x) {
            throw new ServiceException("Unkonwn host name", x);
        }
    }

    /**
     * Gets the cluster name.
     *
     * @return the cluster name
     */
    public String getClusterName() {
        return clusterName;
    }

    /**
     * Gets the host address.
     *
     * @return the host address
     */
    public String getHostAddress() {
        return hostAddress;
    }

    /**
     * Gets the keyspace name.
     *
     * @return the keyspace name
     */
    public String getKeyspaceName() {
        return keyspaceName;
    }

}
