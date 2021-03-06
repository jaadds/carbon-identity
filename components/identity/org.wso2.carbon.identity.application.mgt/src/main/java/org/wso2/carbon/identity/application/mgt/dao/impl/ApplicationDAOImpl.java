/*
 *Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *WSO2 Inc. licenses this file to you under the Apache License,
 *Version 2.0 (the "License"); you may not use this file except
 *in compliance with the License.
 *You may obtain a copy of the License at
 *
 *http://www.apache.org/licenses/LICENSE-2.0
 *
 *Unless required by applicable law or agreed to in writing,
 *software distributed under the License is distributed on an
 *"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *KIND, either express or implied.  See the License for the
 *specific language governing permissions and limitations
 *under the License.
 */

package org.wso2.carbon.identity.application.mgt.dao.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.*;
import org.wso2.carbon.identity.application.common.persistence.JDBCPersistenceManager;
import org.wso2.carbon.identity.application.common.util.CharacterEncoder;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationManagementUtil;
import org.wso2.carbon.identity.application.mgt.ApplicationConstants;
import org.wso2.carbon.identity.application.mgt.ApplicationMgtDBQueries;
import org.wso2.carbon.identity.application.mgt.ApplicationMgtSystemConfig;
import org.wso2.carbon.identity.application.mgt.ApplicationMgtUtil;
import org.wso2.carbon.identity.application.mgt.dao.ApplicationDAO;
import org.wso2.carbon.identity.application.mgt.dao.IdentityProviderDAO;
import org.wso2.carbon.identity.application.mgt.internal.ApplicationManagementServiceComponent;
import org.wso2.carbon.identity.application.mgt.internal.ApplicationManagementServiceComponentHolder;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.DBUtils;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;

/**
 * This class access the IDN_APPMGT database to store/update and delete application configurations.
 * The IDN_APPMGT database contains few tables
 * <ul>
 * <li>IDN_APPMGT_APP</li>
 * <li>IDN_APPMGT_CLIENT</li>
 * <li>IDN_APPMGT_STEP</li>
 * <li>IDN_APPMGT_STEP_IDP</li>
 * <li>IDN_APPMGT_CLAIM_MAPPING</li>
 * <li>IDN_APPMGT_ROLE_MAPPING</li>
 * </ul>
 */
public class ApplicationDAOImpl implements ApplicationDAO {

    Log log = LogFactory.getLog(ApplicationDAOImpl.class);
    boolean debugMode = log.isDebugEnabled();

    /**
     * Stores basic application information and meta-data such as the application name, creator and
     * tenant.
     *
     * @param serviceProvider
     * @throws IdentityApplicationManagementException
     */
    public int createApplication(ServiceProvider serviceProvider, String tenantDomain)
            throws IdentityApplicationManagementException {

        // get logged-in users tenant identifier.
        int tenantID = -123;

        if (tenantDomain != null) {
            try {
                tenantID = ApplicationManagementServiceComponentHolder.getRealmService()
                        .getTenantManager().getTenantId(tenantDomain);
            } catch (UserStoreException e1) {
                throw new IdentityApplicationManagementException("Error while reading application");
            }
        }

        String qualifiedUsername = CarbonContext.getThreadLocalCarbonContext().getUsername();
        if (ApplicationConstants.LOCAL_SP.equals(serviceProvider.getApplicationName())) {
            qualifiedUsername = CarbonConstants.REGISTRY_SYSTEM_USERNAME;
        }
        String username = UserCoreUtil.removeDomainFromName(qualifiedUsername);
        String userStoreDomain = UserCoreUtil.extractDomainFromName(qualifiedUsername);
        String applicationName = serviceProvider.getApplicationName();
        String description = serviceProvider.getDescription();

        if (applicationName == null) {
            // check for required attributes.
            throw new IdentityApplicationManagementException("Application Name is required.");
        }

        if (ApplicationManagementServiceComponent.getFileBasedSPs().containsKey(applicationName)) {
            throw new IdentityApplicationManagementException(
                    "Application with the same name laoded from the file system.");
        }

        if (debugMode) {
            log.debug("Creating Application " + applicationName + " for user " + qualifiedUsername);
        }

        Connection connection = null;
        PreparedStatement storeAppPrepStmt = null;
        ResultSet results = null;

        try {
            connection = JDBCPersistenceManager.getInstance().getDBConnection();
            String dbProductName = connection.getMetaData().getDatabaseProductName();
            storeAppPrepStmt = connection.prepareStatement(
                    ApplicationMgtDBQueries.STORE_BASIC_APPINFO, new String[]{
                            DBUtils.getConvertedAutoGeneratedColumnName(dbProductName, "ID")});

            // TENANT_ID, APP_NAME, USER_STORE, USERNAME, DESCRIPTION, AUTH_TYPE
            storeAppPrepStmt.setInt(1, tenantID);
            storeAppPrepStmt.setString(2, CharacterEncoder.getSafeText(applicationName));
            storeAppPrepStmt.setString(3, CharacterEncoder.getSafeText(userStoreDomain));
            storeAppPrepStmt.setString(4, CharacterEncoder.getSafeText(username));
            storeAppPrepStmt.setString(5, CharacterEncoder.getSafeText(description));
            // by default authentication type would be default.
            // default authenticator is defined system-wide - in the configuration file.
            storeAppPrepStmt.setString(6, ApplicationConstants.AUTH_TYPE_DEFAULT);
            storeAppPrepStmt.execute();

            results = storeAppPrepStmt.getGeneratedKeys();

            if (!connection.getAutoCommit()) {
                connection.commit();
            }

            int applicationId = 0;
            if (results.next()) {
                applicationId = results.getInt(1);
            }
            // some JDBC Drivers returns this in the result, some don't
            if (applicationId == 0) {
                if (debugMode) {
                    log.debug("JDBC Driver did not return the application id, executing Select operation");
                }
                applicationId = getApplicationIDByName(applicationName, tenantID, connection);
            }

            if (debugMode) {
                log.debug("Application Stored successfully with application id " + applicationId);
            }

            return applicationId;

        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException sql) {
                throw new IdentityApplicationManagementException(
                        "Error while Creating Application", sql);
            }
            throw new IdentityApplicationManagementException("Error while Creating Application", e);
        } finally {
            IdentityApplicationManagementUtil.closeResultSet(results);
            IdentityApplicationManagementUtil.closeStatement(storeAppPrepStmt);
            IdentityApplicationManagementUtil.closeConnection(connection);
        }
    }

    /**
     *
     */
    public void updateApplication(ServiceProvider serviceProvider)
            throws IdentityApplicationManagementException {

        Connection connection = null;
        int applicationId = serviceProvider.getApplicationID();

        try {
            connection = IdentityApplicationManagementUtil.getDBConnection();
            if (ApplicationManagementServiceComponent.getFileBasedSPs().containsKey(
                    serviceProvider.getApplicationName())) {
                throw new IdentityApplicationManagementException(
                        "Application with the same name laoded from the file system.");
            }

            // update basic information of the application.
            // you can change application name, description, isSasApp...
            updateBasicApplicationData(applicationId, serviceProvider.getApplicationName(),
                    serviceProvider.getDescription(), serviceProvider.isSaasApp(), connection);
            updateInboundProvisioningConfiguration(applicationId,
                    serviceProvider.getInboundProvisioningConfig(), connection);

            // delete all in-bound authentication requests.
            deleteInboundAuthRequestConfiguration(serviceProvider.getApplicationID(), connection);

            // update all in-bound authentication requests.
            updateInboundAuthRequestConfiguration(serviceProvider.getApplicationID(),
                    serviceProvider.getInboundAuthenticationConfig(), connection);

            // delete local and out-bound authentication configuration.
            deleteLocalAndOutboundAuthenticationConfiguration(applicationId, connection);

            // update local and out-bound authentication configuration.
            updateLocalAndOutboundAuthenticationConfiguration(serviceProvider.getApplicationID(),
                    serviceProvider.getLocalAndOutBoundAuthenticationConfig(), connection);

            deleteRequestPathAuthenticators(applicationId, connection);
            updateRequestPathAuthenticators(applicationId,
                    serviceProvider.getRequestPathAuthenticatorConfigs(), connection);

            deteClaimConfiguration(applicationId, connection);
            updateClaimConfiguration(serviceProvider.getApplicationID(),
                    serviceProvider.getClaimConfig(), applicationId, connection);

            deleteOutboundProvisioningConfiguration(applicationId, connection);
            updateOutboundProvisioningConfiguration(applicationId,
                    serviceProvider.getOutboundProvisioningConfig(), connection);

            deletePermissionAndRoleConfiguration(applicationId, connection);
            updatePermissionAndRoleConfiguration(serviceProvider.getApplicationID(),
                    serviceProvider.getPermissionAndRoleConfig(), connection);

            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (IdentityApplicationManagementException e) {
            try {
                connection.rollback();
            } catch (SQLException e1) {
                throw new IdentityApplicationManagementException(
                        "Failed to update service provider " + applicationId, e);
            }
            throw new IdentityApplicationManagementException("Failed to update service provider "
                    + applicationId, e);
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException e1) {
                throw new IdentityApplicationManagementException(
                        "Failed to update service provider " + applicationId, e);
            }
            throw new IdentityApplicationManagementException("Failed to update service provider "
                    + applicationId, e);
        } catch (UserStoreException e) {
            try {
                connection.rollback();
            } catch (SQLException e1) {
                throw new IdentityApplicationManagementException(
                        "Failed to update service provider " + applicationId, e);
            }
            throw new IdentityApplicationManagementException("Failed to update service provider "
                    + applicationId, e);
        } finally {
            IdentityApplicationManagementUtil.closeConnection(connection);
        }
    }

    /**
     * @param applicationId
     * @param applicationName
     * @param description
     * @param connection
     * @throws SQLException
     * @throws UserStoreException
     * @throws IdentityApplicationManagementException
     */
    private void updateBasicApplicationData(int applicationId, String applicationName,
                                            String description, boolean isSaasApp, Connection connection) throws SQLException, UserStoreException,
            IdentityApplicationManagementException {

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        String storedAppName = null;

        if (applicationName == null) {
            // check for required attributes.
            throw new IdentityApplicationManagementException("Application Name is required.");
        }

        if (debugMode) {
            log.debug("Updating Application with ID: " + applicationId);
        }
        // reads back the Application Name. This is to check if the Application
        // has been renamed
        storedAppName = getApplicationName(applicationId, connection);

        if (debugMode) {
            log.debug("Stored Application Name " + storedAppName);
        }

        // only if the application has been renamed
        if (!applicationName.equalsIgnoreCase(storedAppName)) {
            // rename the role
            ApplicationMgtUtil.renameRole(storedAppName, applicationName);
            if (debugMode) {
                log.debug("Renaming application role from " + storedAppName + " to "
                        + applicationName);
            }
        }

        // update the application data
        PreparedStatement storeAppPrepStmt = null;
        try {
            storeAppPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.UPDATE_BASIC_APPINFO);
            // SET APP_NAME=?, DESCRIPTION=? IS_SAAS_APP=? WHERE TENANT_ID= ? AND ID = ?
            storeAppPrepStmt.setString(1, CharacterEncoder.getSafeText(applicationName));
            storeAppPrepStmt.setString(2, CharacterEncoder.getSafeText(description));
            storeAppPrepStmt.setString(3, isSaasApp ? "1" : "0");
            storeAppPrepStmt.setInt(4, tenantID);
            storeAppPrepStmt.setInt(5, applicationId);
            storeAppPrepStmt.executeUpdate();

        } finally {
            IdentityApplicationManagementUtil.closeStatement(storeAppPrepStmt);
        }

        if (debugMode) {
            log.debug("Updated Application successfully");
        }

    }

    /**
     * @param applicationId
     * @param inBoundAuthenticationConfig
     * @param connection
     * @throws SQLException
     */
    private void updateInboundAuthRequestConfiguration(int applicationId,
                                                       InboundAuthenticationConfig inBoundAuthenticationConfig, Connection connection)
            throws SQLException {
        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        PreparedStatement inboundAuthReqConfigPrepStmt = null;

        try {
            if (inBoundAuthenticationConfig == null
                    || inBoundAuthenticationConfig.getInboundAuthenticationRequestConfigs() == null
                    || inBoundAuthenticationConfig.getInboundAuthenticationRequestConfigs().length == 0) {
                // no in-bound authentication requests defined.
                return;
            }

            inboundAuthReqConfigPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.STORE_CLIENT_INFO);
            InboundAuthenticationRequestConfig[] authRequests = inBoundAuthenticationConfig
                    .getInboundAuthenticationRequestConfigs();

            for (InboundAuthenticationRequestConfig authRequest : authRequests) {
                if (authRequest == null || authRequest.getInboundAuthKey() == null
                        || authRequest.getInboundAuthType() == null) {
                    log.warn("Invalid in-bound authentication request");
                    // not a valid authentication request. Must have client and a type.
                    continue;
                }
                // TENANT_ID, INBOUND_AUTH_KEY,INBOUND_AUTH_TYPE,PROP_NAME, PROP_VALUE, APP_ID

                Property[] properties = authRequest.getProperties();

                if (properties != null && properties.length > 0) {
                    for (Property prop : properties) {
                        inboundAuthReqConfigPrepStmt.setInt(1, tenantID);
                        inboundAuthReqConfigPrepStmt.setString(2,
                                CharacterEncoder.getSafeText(authRequest.getInboundAuthKey()));
                        inboundAuthReqConfigPrepStmt.setString(3,
                                CharacterEncoder.getSafeText(authRequest.getInboundAuthType()));
                        inboundAuthReqConfigPrepStmt.setString(4,
                                CharacterEncoder.getSafeText(prop.getName()));
                        inboundAuthReqConfigPrepStmt.setString(5,
                                CharacterEncoder.getSafeText(prop.getValue()));
                        inboundAuthReqConfigPrepStmt.setInt(6, applicationId);
                        inboundAuthReqConfigPrepStmt.addBatch();
                    }
                } else {
                    inboundAuthReqConfigPrepStmt.setInt(1, tenantID);
                    inboundAuthReqConfigPrepStmt.setString(2,
                            CharacterEncoder.getSafeText(authRequest.getInboundAuthKey()));
                    inboundAuthReqConfigPrepStmt.setString(3,
                            CharacterEncoder.getSafeText(authRequest.getInboundAuthType()));
                    inboundAuthReqConfigPrepStmt.setString(4, null);
                    inboundAuthReqConfigPrepStmt.setString(5, null);
                    inboundAuthReqConfigPrepStmt.setInt(6, applicationId);
                    inboundAuthReqConfigPrepStmt.addBatch();
                }

                if (debugMode) {
                    log.debug("Updating inbound authentication request configuration of the application "
                            + applicationId
                            + "inbound auth key: "
                            + authRequest.getInboundAuthKey()
                            + " inbound auth type: "
                            + authRequest.getInboundAuthType());
                }
            }

            inboundAuthReqConfigPrepStmt.executeBatch();
        } finally {
            IdentityApplicationManagementUtil.closeStatement(inboundAuthReqConfigPrepStmt);
        }
    }

    /**
     * @param applicationId
     * @param inBoundProvisioningConfig
     * @param connection
     * @throws SQLException
     */
    private void updateInboundProvisioningConfiguration(int applicationId,
                                                        InboundProvisioningConfig inBoundProvisioningConfig, Connection connection)
            throws SQLException {

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        PreparedStatement inboundProConfigPrepStmt = null;

        try {
            if (inBoundProvisioningConfig == null
                    || inBoundProvisioningConfig.getProvisioningUserStore() == null) {
                // no in-bound authentication requests defined.
                return;
            }

            inboundProConfigPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.UPDATE_BASIC_APPINFO_WITH_PRO_USERSTORE);

            // PROVISIONING_USERSTORE_DOMAIN=?
            inboundProConfigPrepStmt.setString(1, CharacterEncoder
                    .getSafeText(inBoundProvisioningConfig.getProvisioningUserStore()));
            inboundProConfigPrepStmt.setInt(2, tenantID);
            inboundProConfigPrepStmt.setInt(3, applicationId);
            inboundProConfigPrepStmt.execute();

        } finally {
            IdentityApplicationManagementUtil.closeStatement(inboundProConfigPrepStmt);
        }
    }

    /**
     * @param applicationId
     * @param outBoundProvisioningConfig
     * @param connection
     * @throws SQLException
     */
    private void updateOutboundProvisioningConfiguration(int applicationId,
                                                         OutboundProvisioningConfig outBoundProvisioningConfig, Connection connection)
            throws SQLException {

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        PreparedStatement outboundProConfigPrepStmt = null;

        IdentityProvider[] proProviders = outBoundProvisioningConfig
                .getProvisioningIdentityProviders();

        try {
            if (outBoundProvisioningConfig == null || proProviders == null
                    || proProviders.length == 0) {
                // no in-bound authentication requests defined.
                return;
            }

            outboundProConfigPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.STORE_PRO_CONNECTORS);
            // TENANT_ID, IDP_NAME, CONNECTOR_NAME, APP_ID

            for (IdentityProvider proProvider : proProviders) {
                if (proProvider != null) {
                    ProvisioningConnectorConfig proConnector = proProvider
                            .getDefaultProvisioningConnectorConfig();
                    if (proConnector == null) {
                        continue;
                    }

                    String jitEnabled = "0";

                    if (proProvider.getJustInTimeProvisioningConfig() != null
                            && proProvider.getJustInTimeProvisioningConfig()
                            .isProvisioningEnabled()) {
                        jitEnabled = "1";
                    }

                    String blocking = "0";

                    if (proProvider.getDefaultProvisioningConnectorConfig() != null
                            && proProvider.getDefaultProvisioningConnectorConfig().isBlocking()) {
                        blocking = "1";
                    }

                    outboundProConfigPrepStmt.setInt(1, tenantID);
                    outboundProConfigPrepStmt.setString(2,
                            CharacterEncoder.getSafeText(proProvider.getIdentityProviderName()));
                    outboundProConfigPrepStmt.setString(3,
                            CharacterEncoder.getSafeText(proConnector.getName()));
                    outboundProConfigPrepStmt.setInt(4, applicationId);
                    outboundProConfigPrepStmt
                            .setString(5, CharacterEncoder.getSafeText(jitEnabled));
                    outboundProConfigPrepStmt.setString(6, CharacterEncoder.getSafeText(blocking));
                    outboundProConfigPrepStmt.addBatch();

                }
            }

            outboundProConfigPrepStmt.executeBatch();

        } finally {
            IdentityApplicationManagementUtil.closeStatement(outboundProConfigPrepStmt);
        }
    }

    /**
     * @param applicationId
     * @param connection
     * @return
     * @throws SQLException
     */
    private InboundProvisioningConfig getInboundProvisioningConfiguration(int applicationId,
                                                                          Connection connection, int tenantID) throws SQLException {

        PreparedStatement inboundProConfigPrepStmt = null;
        InboundProvisioningConfig inBoundProvisioningConfig = new InboundProvisioningConfig();
        ResultSet resultSet = null;

        try {

            inboundProConfigPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_PRO_USERSTORE_BY_APP_ID);
            // PROVISIONING_USERSTORE_DOMAIN
            inboundProConfigPrepStmt.setInt(1, tenantID);
            inboundProConfigPrepStmt.setInt(2, applicationId);
            resultSet = inboundProConfigPrepStmt.executeQuery();

            while (resultSet.next()) {
                inBoundProvisioningConfig.setProvisioningUserStore(resultSet.getString(1));
            }

        } finally {
            IdentityApplicationManagementUtil.closeStatement(inboundProConfigPrepStmt);
        }
        return inBoundProvisioningConfig;
    }

    /**
     * @param applicationId
     * @param connection
     * @return
     * @throws SQLException
     */
    private OutboundProvisioningConfig getOutboundProvisioningConfiguration(int applicationId,
                                                                            Connection connection, int tenantID) throws SQLException {

        PreparedStatement outboundProConfigPrepStmt = null;
        OutboundProvisioningConfig outBoundProvisioningConfig = new OutboundProvisioningConfig();
        ResultSet resultSet = null;
        List<IdentityProvider> idpProConnectors = new ArrayList<IdentityProvider>();

        try {

            outboundProConfigPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_PRO_CONNECTORS_BY_APP_ID);
            // IDP_NAME, CONNECTOR_NAM
            outboundProConfigPrepStmt.setInt(1, applicationId);
            outboundProConfigPrepStmt.setInt(2, tenantID);
            resultSet = outboundProConfigPrepStmt.executeQuery();

            while (resultSet.next()) {
                ProvisioningConnectorConfig proConnector = null;
                IdentityProvider fedIdp = null;

                fedIdp = new IdentityProvider();
                fedIdp.setIdentityProviderName(resultSet.getString(1));

                proConnector = new ProvisioningConnectorConfig();
                proConnector.setName(resultSet.getString(2));

                if ("1".equals(resultSet.getString(3))) {
                    JustInTimeProvisioningConfig jitConfig = new JustInTimeProvisioningConfig();
                    jitConfig.setProvisioningEnabled(true);
                    fedIdp.setJustInTimeProvisioningConfig(jitConfig);
                }

                if ("1".equals(resultSet.getString(4))) {
                    proConnector.setBlocking(true);
                } else {
                    proConnector.setBlocking(false);
                }

                fedIdp.setDefaultProvisioningConnectorConfig(proConnector);
                idpProConnectors.add(fedIdp);

            }

            outBoundProvisioningConfig.setProvisioningIdentityProviders(idpProConnectors
                    .toArray(new IdentityProvider[idpProConnectors.size()]));

        } finally {
            IdentityApplicationManagementUtil.closeStatement(outboundProConfigPrepStmt);
        }
        return outBoundProvisioningConfig;
    }

    /**
     * @param applicationId
     * @param localAndOutboundAuthConfig
     * @param connection
     * @throws SQLException
     * @throws IdentityApplicationManagementException
     */
    private void updateLocalAndOutboundAuthenticationConfiguration(int applicationId,
                                                                   LocalAndOutboundAuthenticationConfig localAndOutboundAuthConfig, Connection connection)
            throws SQLException, IdentityApplicationManagementException {

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        PreparedStatement updateAuthTypePrepStmt = null;
        if (localAndOutboundAuthConfig == null) {
            // no local or out-bound configuration for this service provider.
            return;
        }

        PreparedStatement storeSendAuthListOfIdPsPrepStmt = null;
        try {
            storeSendAuthListOfIdPsPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.UPDATE_BASIC_APPINFO_WITH_SEND_AUTH_LIST_OF_IDPS);
            // IS_SEND_LOCAL_SUBJECT_ID=? WHERE TENANT_ID= ? AND ID = ?
            storeSendAuthListOfIdPsPrepStmt.setString(1, localAndOutboundAuthConfig
                    .isAlwaysSendBackAuthenticatedListOfIdPs() ? "1" : "0");
            storeSendAuthListOfIdPsPrepStmt.setInt(2, tenantID);
            storeSendAuthListOfIdPsPrepStmt.setInt(3, applicationId);
            storeSendAuthListOfIdPsPrepStmt.executeUpdate();
        } finally {
            IdentityApplicationManagementUtil.closeStatement(storeSendAuthListOfIdPsPrepStmt);
        }

        PreparedStatement storeSubjectClaimUri = null;
        try {
            storeSubjectClaimUri = connection
                    .prepareStatement(ApplicationMgtDBQueries.UPDATE_BASIC_APPINFO_WITH_SUBJECT_CLAIM_URI);
            // SUBJECT_CLAIM_URI=? WHERE TENANT_ID= ? AND ID = ?
            storeSubjectClaimUri.setString(1,
                    CharacterEncoder.getSafeText(localAndOutboundAuthConfig.getSubjectClaimUri()));
            storeSubjectClaimUri.setInt(2, tenantID);
            storeSubjectClaimUri.setInt(3, applicationId);
            storeSubjectClaimUri.executeUpdate();
        } finally {
            IdentityApplicationManagementUtil.closeStatement(storeSubjectClaimUri);
        }

        AuthenticationStep[] authSteps = localAndOutboundAuthConfig.getAuthenticationSteps();

        if (authSteps == null || authSteps.length == 0) {
            // if no authentication steps defined - it should be the default behavior.
            localAndOutboundAuthConfig
                    .setAuthenticationType(ApplicationConstants.AUTH_TYPE_DEFAULT);
        }

        try {
            if (localAndOutboundAuthConfig.getAuthenticationType() == null) {
                // no authentication type defined - set to default.
                localAndOutboundAuthConfig
                        .setAuthenticationType(ApplicationConstants.AUTH_TYPE_DEFAULT);
            }

            updateAuthTypePrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.UPDATE_BASIC_APPINFO_WITH_AUTH_TYPE);
            // AUTH_TYPE=? WHERE TENANT_ID= ? AND ID = ?
            updateAuthTypePrepStmt.setString(1, CharacterEncoder
                    .getSafeText(localAndOutboundAuthConfig.getAuthenticationType()));
            updateAuthTypePrepStmt.setInt(2, tenantID);
            updateAuthTypePrepStmt.setInt(3, applicationId);
            updateAuthTypePrepStmt.execute();
        } finally {
            IdentityApplicationManagementUtil.closeStatement(updateAuthTypePrepStmt);
        }

        if (authSteps != null && authSteps.length > 0) {
            // we have authentications steps defined.
            PreparedStatement storeStepIDPAuthnPrepStmt = null;
            storeStepIDPAuthnPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.STORE_STEP_IDP_AUTH);
            try {

                if (ApplicationConstants.AUTH_TYPE_LOCAL
                        .equalsIgnoreCase(localAndOutboundAuthConfig.getAuthenticationType())) {
                    // for local authentication there can only be only one authentication step and
                    // only one local authenticator.
                    if (authSteps.length != 1
                            || authSteps[0] == null
                            || authSteps[0].getLocalAuthenticatorConfigs() == null
                            || authSteps[0].getLocalAuthenticatorConfigs().length != 1
                            || (authSteps[0].getFederatedIdentityProviders() != null && authSteps[0]
                            .getFederatedIdentityProviders().length >= 1)) {
                        String errorMessage = "Invalid local authentication configuration."
                                + " For local authentication there can only be only one authentication step and only one local authenticator";
                        throw new IdentityApplicationManagementException(errorMessage);
                    }
                } else if (ApplicationConstants.AUTH_TYPE_FEDERATED
                        .equalsIgnoreCase(localAndOutboundAuthConfig.getAuthenticationType())) {
                    // for federated authentication there can only be only one authentication step
                    // and only one federated authenticator - which is the default authenticator of
                    // the corresponding authenticator.
                    if (authSteps.length != 1 || authSteps[0] == null
                            || authSteps[0].getFederatedIdentityProviders() == null
                            || authSteps[0].getFederatedIdentityProviders().length != 1
                            || authSteps[0].getLocalAuthenticatorConfigs().length > 0) {
                        String errorMessage = "Invalid federated authentication configuration."
                                + " For federated authentication there can only be only one authentication step and only one federated authenticator";
                        throw new IdentityApplicationManagementException(errorMessage);
                    }

                    IdentityProvider fedIdp = authSteps[0].getFederatedIdentityProviders()[0];
                    IdentityProviderDAO idpDAO = ApplicationMgtSystemConfig.getInstance()
                            .getIdentityProviderDAO();

                    String defualtAuthName = idpDAO.getDefaultAuthenticator(fedIdp
                            .getIdentityProviderName());

                    // set the default authenticator.
                    FederatedAuthenticatorConfig defaultAuth = new FederatedAuthenticatorConfig();
                    defaultAuth.setName(defualtAuthName);
                    fedIdp.setDefaultAuthenticatorConfig(defaultAuth);
                    fedIdp.setFederatedAuthenticatorConfigs(new FederatedAuthenticatorConfig[]{defaultAuth});
                }

                // iterating through each step.
                for (AuthenticationStep authStep : authSteps) {
                    int stepId = 0;

                    IdentityProvider[] federatedIdps = authStep.getFederatedIdentityProviders();

                    // an authentication step should have at least one federated identity
                    // provider or a local authenticator.
                    if ((federatedIdps == null || federatedIdps.length == 0)
                            && (authStep.getLocalAuthenticatorConfigs() == null || authStep
                            .getLocalAuthenticatorConfigs().length == 0)) {
                        String errorMesssage = "Invalid authentication configuration."
                                + "An authentication step should have at least one federated identity "
                                + "provider or a local authenticator";
                        throw new IdentityApplicationManagementException(errorMesssage);
                    }

                    // we have valid federated identity providers.
                    PreparedStatement storeStepPrepStmtz = null;
                    ResultSet result = null;

                    try {
                        String dbProductName = connection.getMetaData().getDatabaseProductName();
                        storeStepPrepStmtz = connection.prepareStatement(
                                ApplicationMgtDBQueries.STORE_STEP_INFO, new String[]{
                                        DBUtils.getConvertedAutoGeneratedColumnName(dbProductName, "ID")});
                        // TENANT_ID, STEP_ORDER, APP_ID
                        storeStepPrepStmtz.setInt(1, tenantID);
                        storeStepPrepStmtz.setInt(2, authStep.getStepOrder());
                        storeStepPrepStmtz.setInt(3, applicationId);
                        storeStepPrepStmtz.setString(4, authStep.isSubjectStep() ? "1" : "0");
                        storeStepPrepStmtz.setString(5, authStep.isAttributeStep() ? "1" : "0");
                        storeStepPrepStmtz.execute();

                        result = storeStepPrepStmtz.getGeneratedKeys();

                        if (result.next()) {
                            stepId = result.getInt(1);
                        }
                    } finally {
                        IdentityApplicationManagementUtil.closeResultSet(result);
                        IdentityApplicationManagementUtil.closeStatement(storeStepPrepStmtz);
                    }

                    if (authStep.getLocalAuthenticatorConfigs() != null
                            && authStep.getLocalAuthenticatorConfigs().length > 0) {

                        for (LocalAuthenticatorConfig lclAuthenticator : authStep
                                .getLocalAuthenticatorConfigs()) {
                            // set the identity provider name to LOCAL.
                            int authenticatorId = getAuthentictorID(connection, tenantID,
                                    ApplicationConstants.LOCAL_IDP_NAME, lclAuthenticator.getName());
                            if (authenticatorId < 0) {
                                authenticatorId = addAuthenticator(connection, tenantID,
                                        ApplicationConstants.LOCAL_IDP_NAME,
                                        lclAuthenticator.getName(),
                                        lclAuthenticator.getDisplayName());
                            }
                            if (authenticatorId > 0) {
                                // ID, TENANT_ID, AUTHENTICATOR_ID
                                storeStepIDPAuthnPrepStmt.setInt(1, stepId);
                                storeStepIDPAuthnPrepStmt.setInt(2, tenantID);
                                storeStepIDPAuthnPrepStmt.setInt(3, authenticatorId);
                                storeStepIDPAuthnPrepStmt.addBatch();
                            }

                            if (debugMode) {
                                log.debug("Updating Local IdP of Application " + applicationId
                                        + " Step Order: " + authStep.getStepOrder() + " IdP: "
                                        + ApplicationConstants.LOCAL_IDP + " Authenticator: "
                                        + lclAuthenticator.getName());
                            }
                        }
                    }

                    // we have federated identity providers.
                    if (federatedIdps != null && federatedIdps.length > 0) {

                        // iterating through each IDP of the step
                        for (IdentityProvider federatedIdp : federatedIdps) {
                            String idpName = federatedIdp.getIdentityProviderName();

                            // the identity provider name wso2carbon-local-idp is reserved.
                            if (ApplicationConstants.LOCAL_IDP.equalsIgnoreCase(idpName)) {
                                throw new IdentityApplicationManagementException(
                                        "The federated IdP name cannot be equal to "
                                                + ApplicationConstants.LOCAL_IDP);
                            }

                            FederatedAuthenticatorConfig[] authenticators = federatedIdp
                                    .getFederatedAuthenticatorConfigs();

                            if (authenticators != null && authenticators.length > 0) {

                                for (FederatedAuthenticatorConfig authenticator : authenticators) {
                                    // ID, TENANT_ID, AUTHENTICATOR_ID
                                    int authenticatorId = getAuthentictorID(connection, tenantID,
                                            idpName, authenticator.getName());
                                    if (authenticatorId > 0) {
                                        if (authenticator != null) {
                                            storeStepIDPAuthnPrepStmt.setInt(1, stepId);
                                            storeStepIDPAuthnPrepStmt.setInt(2, tenantID);
                                            storeStepIDPAuthnPrepStmt.setInt(3, authenticatorId);
                                            storeStepIDPAuthnPrepStmt.addBatch();

                                            if (debugMode) {
                                                log.debug("Updating Federated IdP of Application "
                                                        + applicationId + " Step Order: "
                                                        + authStep.getStepOrder() + " IdP: "
                                                        + idpName + " Authenticator: "
                                                        + authenticator);
                                            }
                                        }
                                    }
                                }
                            }

                        }
                    }
                }

                storeStepIDPAuthnPrepStmt.executeBatch();
            } finally {
                IdentityApplicationManagementUtil.closeStatement(storeStepIDPAuthnPrepStmt);
            }
        }
    }

    /**
     * @param applicationId
     * @param claimConfiguration
     * @param applicationID
     * @param connection
     * @throws SQLException
     */
    private void updateClaimConfiguration(int applicationId, ClaimConfig claimConfiguration,
                                          int applicationID, Connection connection) throws SQLException {

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        PreparedStatement storeRoleClaimPrepStmt = null;
        PreparedStatement storeClaimDialectPrepStmt = null;
        PreparedStatement storeSendLocalSubIdPrepStmt = null;

        if (claimConfiguration == null) {
            return;
        }

        try {
            // update the application data
            String roleClaim = claimConfiguration.getRoleClaimURI();
            if (roleClaim != null) {
                storeRoleClaimPrepStmt = connection
                        .prepareStatement(ApplicationMgtDBQueries.UPDATE_BASIC_APPINFO_WITH_ROLE_CLAIM);
                // ROLE_CLAIM=? WHERE TENANT_ID= ? AND ID =
                storeRoleClaimPrepStmt.setString(1, CharacterEncoder.getSafeText(roleClaim));
                storeRoleClaimPrepStmt.setInt(2, tenantID);
                storeRoleClaimPrepStmt.setInt(3, applicationId);
                storeRoleClaimPrepStmt.executeUpdate();
            }

        } finally {
            IdentityApplicationManagementUtil.closeStatement(storeRoleClaimPrepStmt);
        }

        try {
            storeClaimDialectPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.UPDATE_BASIC_APPINFO_WITH_CLAIM_DIALEECT);
            // IS_LOCAL_CLAIM_DIALECT=? WHERE TENANT_ID= ? AND ID = ?
            storeClaimDialectPrepStmt.setString(1, claimConfiguration.isLocalClaimDialect() ? "1"
                    : "0");
            storeClaimDialectPrepStmt.setInt(2, tenantID);
            storeClaimDialectPrepStmt.setInt(3, applicationId);
            storeClaimDialectPrepStmt.executeUpdate();
        } finally {
            IdentityApplicationManagementUtil.closeStatement(storeClaimDialectPrepStmt);
        }

        try {
            storeSendLocalSubIdPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.UPDATE_BASIC_APPINFO_WITH_SEND_LOCAL_SUB_ID);
            // IS_SEND_LOCAL_SUBJECT_ID=? WHERE TENANT_ID= ? AND ID = ?
            storeSendLocalSubIdPrepStmt.setString(1,
                    claimConfiguration.isAlwaysSendMappedLocalSubjectId() ? "1" : "0");
            storeSendLocalSubIdPrepStmt.setInt(2, tenantID);
            storeSendLocalSubIdPrepStmt.setInt(3, applicationId);
            storeSendLocalSubIdPrepStmt.executeUpdate();
        } finally {
            IdentityApplicationManagementUtil.closeStatement(storeSendLocalSubIdPrepStmt);
        }

        if (claimConfiguration.getClaimMappings() == null
                || claimConfiguration.getClaimMappings().length == 0) {
            return;
        }

        List<ClaimMapping> claimMappings = Arrays.asList(claimConfiguration.getClaimMappings());

        if (claimConfiguration == null || claimMappings.size() < 1) {
            log.debug("No claim mapping found, Skipping ..");
            return;
        }

        PreparedStatement storeClaimMapPrepStmt = null;
        try {
            storeClaimMapPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.STORE_CLAIM_MAPPING);

            for (ClaimMapping mapping : claimMappings) {
                if (mapping.getLocalClaim() == null
                        || mapping.getLocalClaim().getClaimUri() == null
                        || mapping.getRemoteClaim().getClaimUri() == null
                        || mapping.getRemoteClaim() == null) {
                    continue;
                }
                // TENANT_ID, IDP_CLAIM, SP_CLAIM, APP_ID, IS_REQUESTED
                storeClaimMapPrepStmt.setInt(1, tenantID);
                storeClaimMapPrepStmt.setString(2,
                        CharacterEncoder.getSafeText(mapping.getLocalClaim().getClaimUri()));
                storeClaimMapPrepStmt.setString(3,
                        CharacterEncoder.getSafeText(mapping.getRemoteClaim().getClaimUri()));
                storeClaimMapPrepStmt.setInt(4, applicationID);
                if (mapping.isRequested()) {
                    storeClaimMapPrepStmt.setString(5, "1");
                } else {
                    storeClaimMapPrepStmt.setString(5, "0");
                }
                storeClaimMapPrepStmt.setString(6,
                        CharacterEncoder.getSafeText(mapping.getDefaultValue()));
                storeClaimMapPrepStmt.addBatch();

                if (debugMode) {
                    log.debug("Storing Claim Mapping. Local Claim: "
                            + mapping.getLocalClaim().getClaimUri() + " SPClaim: "
                            + mapping.getRemoteClaim().getClaimUri());
                }
            }

            storeClaimMapPrepStmt.executeBatch();
        } finally {
            IdentityApplicationManagementUtil.closeStatement(storeClaimMapPrepStmt);
        }
    }

    /**
     * @param applicationID
     * @param permissionsAndRoleConfiguration
     * @param connection
     * @throws SQLException
     */
    private void updatePermissionAndRoleConfiguration(int applicationID,
                                                      PermissionsAndRoleConfig permissionsAndRoleConfiguration, Connection connection)
            throws SQLException {

        if (permissionsAndRoleConfiguration == null
                || permissionsAndRoleConfiguration.getRoleMappings() == null
                || permissionsAndRoleConfiguration.getRoleMappings().length == 0) {
            return;
        }

        RoleMapping[] roleMappings = permissionsAndRoleConfiguration.getRoleMappings();
        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        PreparedStatement storeRoleMapPrepStmt = null;
        try {
            storeRoleMapPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.STORE_ROLE_MAPPING);
            for (RoleMapping roleMapping : roleMappings) {
                // TENANT_ID, IDP_ROLE, SP_ROLE, APP_ID
                storeRoleMapPrepStmt.setInt(1, tenantID);
                storeRoleMapPrepStmt
                        .setString(2, CharacterEncoder.getSafeText(roleMapping.getLocalRole()
                                .getLocalRoleName()));
                storeRoleMapPrepStmt.setString(3,
                        CharacterEncoder.getSafeText(roleMapping.getRemoteRole()));
                storeRoleMapPrepStmt.setInt(4, applicationID);
                storeRoleMapPrepStmt.addBatch();

                if (debugMode) {
                    log.debug("Storing Claim Mapping. IDPRole: " + roleMapping.getLocalRole()
                            + " SPRole: " + roleMapping.getRemoteRole());
                }
            }

            storeRoleMapPrepStmt.executeBatch();
        } finally {
            IdentityApplicationManagementUtil.closeStatement(storeRoleMapPrepStmt);
        }
    }

    /**
     *
     */
    public ServiceProvider getApplication(String applicationName, String tenantDomain)
            throws IdentityApplicationManagementException {

        int applicationId = 0;
        int tenantID = MultitenantConstants.SUPER_TENANT_ID;
        if (tenantDomain != null) {
            try {
                tenantID = ApplicationManagementServiceComponentHolder.getRealmService()
                        .getTenantManager().getTenantId(tenantDomain);
            } catch (UserStoreException e1) {
                throw new IdentityApplicationManagementException("Error while reading application");
            }
        }

        Connection connection = null;
        try {
            connection = IdentityApplicationManagementUtil.getDBConnection();

            // Load basic application data
            ServiceProvider serviceProvider = getBasicApplicationData(applicationName, connection,
                    tenantID);

            if ((serviceProvider == null || serviceProvider.getApplicationName() == null)
                    && ApplicationConstants.LOCAL_SP.equals(applicationName)) {
                ServiceProvider localServiceProvider = new ServiceProvider();
                localServiceProvider.setApplicationName(applicationName);
                localServiceProvider.setDescription("Local Service Provider");
                createApplication(localServiceProvider, tenantDomain);
                serviceProvider = getBasicApplicationData(applicationName, connection, tenantID);
            }

            if (serviceProvider == null) {
                return null;
            }

            applicationId = serviceProvider.getApplicationID();

            serviceProvider.setInboundAuthenticationConfig(getInboundAuthenticationConfig(
                    applicationId, connection, tenantID));
            serviceProvider
                    .setLocalAndOutBoundAuthenticationConfig(getLocalAndOutboundAuthenticationConfig(
                            applicationId, connection, tenantID));

            serviceProvider.setInboundProvisioningConfig(getInboundProvisioningConfiguration(
                    applicationId, connection, tenantID));

            serviceProvider.setOutboundProvisioningConfig(getOutboundProvisioningConfiguration(
                    applicationId, connection, tenantID));

            // Load Claim Mapping
            serviceProvider.setClaimConfig(getClaimConfiguration(applicationId, connection,
                    tenantID));

            // Load Role Mappings
            List<RoleMapping> roleMappings = getRoleMappingOfApplication(applicationId, connection,
                    tenantID);
            PermissionsAndRoleConfig permissionAndRoleConfig = new PermissionsAndRoleConfig();
            permissionAndRoleConfig.setRoleMappings(roleMappings
                    .toArray(new RoleMapping[roleMappings.size()]));
            serviceProvider.setPermissionAndRoleConfig(permissionAndRoleConfig);

            RequestPathAuthenticatorConfig[] requestPathAuthenticators = getRequestPathAuthenticators(
                    applicationId, connection, tenantID);
            serviceProvider.setRequestPathAuthenticatorConfigs(requestPathAuthenticators);
            return serviceProvider;

        } catch (SQLException e) {
            throw new IdentityApplicationManagementException("Failed to update service provider "
                    + applicationId, e);
        } finally {
            IdentityApplicationManagementUtil.closeConnection(connection);
        }
    }

    /**
     * @param applicationName
     * @param connection
     * @return
     * @throws SQLException
     */
    private ServiceProvider getBasicApplicationData(String applicationName, Connection connection,
                                                    int tenantID) throws SQLException {

        ServiceProvider serviceProvider = null;

        if (debugMode) {
            log.debug("Loading Basic Application Data of " + applicationName);
        }

        PreparedStatement loadBasicAppInfoStmt = null;
        ResultSet basicAppDataResultSet = null;
        try {
            loadBasicAppInfoStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_BASIC_APP_INFO_BY_APP_NAME);
            // SELECT * FROM IDN_APPMGT_APP WHERE APP_NAME = ? AND TENANT_ID = ?
            loadBasicAppInfoStmt.setString(1, CharacterEncoder.getSafeText(applicationName));
            loadBasicAppInfoStmt.setInt(2, tenantID);
            basicAppDataResultSet = loadBasicAppInfoStmt.executeQuery();
            // ID, TENANT_ID, APP_NAME, USER_STORE, USERNAME, DESCRIPTION, ROLE_CLAIM, AUTH_TYPE,
            // PROVISIONING_USERSTORE_DOMAIN, IS_LOCAL_CLAIM_DIALECT, IS_SEND_LOCAL_SUBJECT_ID,
            // IS_SEND_AUTH_LIST_OF_IDPS, SUBJECT_CLAIM_URI, IS_SAAS_APP

            if (basicAppDataResultSet.next()) {
                serviceProvider = new ServiceProvider();
                serviceProvider.setApplicationID(basicAppDataResultSet.getInt(1));
                serviceProvider.setApplicationName(basicAppDataResultSet.getString(3));
                serviceProvider.setDescription(basicAppDataResultSet.getString(6));

                User owner = new User();
                owner.setUserName(basicAppDataResultSet.getString(5));
                owner.setTenantId(basicAppDataResultSet.getInt(2));
                owner.setUserStoreDomain(basicAppDataResultSet.getString(4));
                serviceProvider.setOwner(owner);

                ClaimConfig claimConfig = new ClaimConfig();
                claimConfig.setRoleClaimURI(basicAppDataResultSet.getString(7));
                claimConfig.setLocalClaimDialect("1".equals(basicAppDataResultSet.getString(10)));
                claimConfig.setAlwaysSendMappedLocalSubjectId("1".equals(basicAppDataResultSet
                        .getString(11)));
                serviceProvider.setClaimConfig(claimConfig);

                LocalAndOutboundAuthenticationConfig localAndOutboundAuthenticationConfig = new LocalAndOutboundAuthenticationConfig();
                localAndOutboundAuthenticationConfig.setAlwaysSendBackAuthenticatedListOfIdPs("1"
                        .equals(basicAppDataResultSet.getString(12)));
                localAndOutboundAuthenticationConfig.setSubjectClaimUri(basicAppDataResultSet
                        .getString(13));
                serviceProvider
                        .setLocalAndOutBoundAuthenticationConfig(localAndOutboundAuthenticationConfig);

                serviceProvider.setSaasApp("1".equals(basicAppDataResultSet.getString(14)));

                if (debugMode) {
                    log.debug("ApplicationID: " + serviceProvider.getApplicationID()
                            + " ApplicationName: " + serviceProvider.getApplicationName()
                            + " UserName: " + serviceProvider.getOwner().getUserName()
                            + " TenantID: " + serviceProvider.getOwner().getTenantId());
                }
            }

            return serviceProvider;
        } finally {
            IdentityApplicationManagementUtil.closeResultSet(basicAppDataResultSet);
            IdentityApplicationManagementUtil.closeStatement(loadBasicAppInfoStmt);
        }

    }

    /**
     * @param applicationid
     * @param connection
     * @return
     * @throws SQLException
     */
    private String getAuthenticationType(int applicationid, Connection connection)
            throws SQLException {

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        PreparedStatement authTypeStmt = null;
        ResultSet authTypeResultSet = null;
        try {
            authTypeStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_AUTH_TYPE_BY_APP_ID);
            authTypeStmt.setInt(1, applicationid);
            authTypeStmt.setInt(2, tenantID);
            authTypeResultSet = authTypeStmt.executeQuery();

            if (authTypeResultSet.next()) {
                return authTypeResultSet.getString(1);
            }

            return ApplicationConstants.AUTH_TYPE_DEFAULT;

        } finally {
            IdentityApplicationManagementUtil.closeResultSet(authTypeResultSet);
            IdentityApplicationManagementUtil.closeStatement(authTypeStmt);
        }

    }

    /**
     * This method will be heavily used by the Authentication Framework. The framework would ask for
     * application data with the given client key and secrete
     *
     * @param clientId
     * @param type
     * @param tenantDomain
     * @return
     * @throws IdentityApplicationManagementException
     */
    public ServiceProvider getApplicationData(String clientId, String type, String tenantDomain)
            throws IdentityApplicationManagementException {

        if (debugMode) {
            log.debug("Loading Application Data of Client " + clientId);
        }

        int tenantID = -123;

        try {
            tenantID = ApplicationManagementServiceComponentHolder.getRealmService()
                    .getTenantManager().getTenantId(tenantDomain);
        } catch (UserStoreException e1) {
            throw new IdentityApplicationManagementException("Error while reading application");
        }

        String applicationName = null;

        // Reading application name from the database
        Connection connection = null;
        PreparedStatement storeAppPrepStmt = null;
        ResultSet appNameResult = null;
        try {
            connection = JDBCPersistenceManager.getInstance().getDBConnection();
            storeAppPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_APPLICATION_NAME_BY_CLIENT_ID_AND_TYPE);
            storeAppPrepStmt.setString(1, CharacterEncoder.getSafeText(clientId));
            storeAppPrepStmt.setString(2, CharacterEncoder.getSafeText(type));
            storeAppPrepStmt.setInt(3, tenantID);
            appNameResult = storeAppPrepStmt.executeQuery();
            connection.commit();
            if (appNameResult.next()) {
                applicationName = appNameResult.getString(1);
            }

        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            throw new IdentityApplicationManagementException("Error while reading application");
        } finally {
            IdentityApplicationManagementUtil.closeResultSet(appNameResult);
            IdentityApplicationManagementUtil.closeStatement(storeAppPrepStmt);
            IdentityApplicationManagementUtil.closeConnection(connection);
        }

        return getApplication(applicationName, tenantDomain);
    }

    /**
     * @param applicationID
     * @return
     * @throws IdentityApplicationManagementException
     */
    public String getApplicationName(int applicationID)
            throws IdentityApplicationManagementException {
        Connection connection = null;
        try {
            connection = IdentityApplicationManagementUtil.getDBConnection();
            return getApplicationName(applicationID, connection);
        } catch (SQLException e) {
            throw new IdentityApplicationManagementException("Failed loading the application with "
                    + applicationID, e);
        } finally {
            IdentityApplicationManagementUtil.closeConnection(connection);
        }
    }

    /**
     * Reads back the basic application data
     *
     * @param applicationID
     * @param connection
     * @return
     * @throws IdentityApplicationManagementException
     */
    private String getApplicationName(int applicationID, Connection connection) throws SQLException {

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        if (debugMode) {
            log.debug("Loading Application Name for ID: " + applicationID);
        }

        PreparedStatement loadBasicAppInfoStmt = null;
        ResultSet appNameResultSet = null;
        String applicationName = null;

        try {
            loadBasicAppInfoStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_APP_NAME_BY_APP_ID);
            loadBasicAppInfoStmt.setInt(1, applicationID);
            loadBasicAppInfoStmt.setInt(2, tenantID);
            appNameResultSet = loadBasicAppInfoStmt.executeQuery();

            if (appNameResultSet.next()) {
                applicationName = appNameResultSet.getString(1);
            }

            if (debugMode) {
                log.debug("ApplicationName : " + applicationName);
            }
            return applicationName;

        } finally {
            IdentityApplicationManagementUtil.closeResultSet(appNameResultSet);
            IdentityApplicationManagementUtil.closeStatement(loadBasicAppInfoStmt);
        }
    }

    /**
     * Returns the application ID for a given application name
     *
     * @param applicationName
     * @param tenantID
     * @param connection
     * @return
     * @throws IdentityApplicationManagementException
     */
    private int getApplicationIDByName(String applicationName, int tenantID, Connection connection)
            throws IdentityApplicationManagementException {

        int applicationId = 0;
        PreparedStatement getAppIDPrepStmt = null;
        ResultSet appidResult = null;

        try {
            getAppIDPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_APP_ID_BY_APP_NAME);
            getAppIDPrepStmt.setString(1, CharacterEncoder.getSafeText(applicationName));
            getAppIDPrepStmt.setInt(2, tenantID);
            appidResult = getAppIDPrepStmt.executeQuery();

            if (!connection.getAutoCommit()) {
                connection.commit();
            }

            if (appidResult.next()) {
                applicationId = appidResult.getInt(1);
            }

        } catch (SQLException e) {
            IdentityApplicationManagementUtil.closeConnection(connection);
            throw new IdentityApplicationManagementException("Error while storing application");
        } finally {
            IdentityApplicationManagementUtil.closeResultSet(appidResult);
            IdentityApplicationManagementUtil.closeStatement(getAppIDPrepStmt);
        }

        return applicationId;
    }

    /**
     * @param applicationId
     * @param connection
     * @return
     * @throws SQLException
     */
    private InboundAuthenticationConfig getInboundAuthenticationConfig(int applicationId,
                                                                       Connection connection, int tenantID) throws SQLException {

        Map<String, InboundAuthenticationRequestConfig> authRequestMap = new HashMap<String, InboundAuthenticationRequestConfig>();

        if (debugMode) {
            log.debug("Reading Clients of Application " + applicationId);
        }

        PreparedStatement getClientInfo = null;
        ResultSet resultSet = null;
        try {

            // INBOUND_AUTH_KEY, INBOUND_AUTH_TYPE, PROP_NAME, PROP_VALUE
            getClientInfo = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_CLIENTS_INFO_BY_APP_ID);

            getClientInfo.setInt(1, applicationId);
            getClientInfo.setInt(2, tenantID);
            resultSet = getClientInfo.executeQuery();

            while (resultSet.next()) {

                InboundAuthenticationRequestConfig inbountAuthRequest = null;
                String authKey = resultSet.getString(1);

                if (!authRequestMap.containsKey(authKey)) {
                    inbountAuthRequest = new InboundAuthenticationRequestConfig();
                    inbountAuthRequest.setInboundAuthKey(authKey);
                    inbountAuthRequest.setInboundAuthType(resultSet.getString(2));
                    inbountAuthRequest.setProperties(new Property[0]);
                    authRequestMap.put(authKey, inbountAuthRequest);
                }

                inbountAuthRequest = authRequestMap.get(authKey);

                String propName = resultSet.getString(3);

                if (propName != null) {
                    Property prop = new Property();
                    prop.setName(propName);
                    prop.setValue(resultSet.getString(4));

                    inbountAuthRequest.setProperties((ApplicationMgtUtil.concatArrays(
                            new Property[]{prop}, inbountAuthRequest.getProperties())));
                }

                if (debugMode) {
                    log.debug("Auth request key: " + inbountAuthRequest.getInboundAuthKey()
                            + " Auth request type: " + inbountAuthRequest.getInboundAuthType());
                }
            }

        } finally {
            IdentityApplicationManagementUtil.closeStatement(getClientInfo);
            IdentityApplicationManagementUtil.closeResultSet(resultSet);
        }

        InboundAuthenticationConfig inboundAuthenticationConfig = new InboundAuthenticationConfig();
        inboundAuthenticationConfig.setInboundAuthenticationRequestConfigs(authRequestMap.values()
                .toArray(new InboundAuthenticationRequestConfig[authRequestMap.size()]));
        return inboundAuthenticationConfig;
    }

    /**
     * @param applicationId
     * @param connection
     * @return
     * @throws SQLException
     */
    private LocalAndOutboundAuthenticationConfig getLocalAndOutboundAuthenticationConfig(
            int applicationId, Connection connection, int tenantId) throws SQLException {
        PreparedStatement getStepInfoPrepStmt = null;
        ResultSet stepInfoResultSet = null;

        if (debugMode) {
            log.debug("Reading Steps of Application " + applicationId);
        }

        try {
            getStepInfoPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_STEPS_INFO_BY_APP_ID);
            // STEP_ORDER, AUTHENTICATOR_ID, IS_SUBJECT_STEP, IS_ATTRIBUTE_STEP
            getStepInfoPrepStmt.setInt(1, applicationId);
            stepInfoResultSet = getStepInfoPrepStmt.executeQuery();

            Map<String, AuthenticationStep> authSteps = new HashMap<String, AuthenticationStep>();
            Map<String, Map<String, List<FederatedAuthenticatorConfig>>> stepFedIdPAuthenticators = new HashMap<String, Map<String, List<FederatedAuthenticatorConfig>>>();
            Map<String, List<LocalAuthenticatorConfig>> stepLocalAuth = new HashMap<String, List<LocalAuthenticatorConfig>>();

            while (stepInfoResultSet.next()) {

                String step = String.valueOf(stepInfoResultSet.getInt(1));
                AuthenticationStep authStep;

                if (authSteps.containsKey(step)) {
                    authStep = authSteps.get(step);
                } else {
                    authStep = new AuthenticationStep();
                    authStep.setStepOrder(stepInfoResultSet.getInt(1));
                    stepLocalAuth.put(step, new ArrayList<LocalAuthenticatorConfig>());
                    stepFedIdPAuthenticators.put(step,
                            new HashMap<String, List<FederatedAuthenticatorConfig>>());
                }

                int authenticatorId = stepInfoResultSet.getInt(2);
                Map<String, String> authenticatorInfo = getAuthenticatorInfo(connection, tenantId,
                        authenticatorId);

                if (authenticatorInfo != null
                        && authenticatorInfo.get(ApplicationConstants.IDP_NAME) != null
                        && ApplicationConstants.LOCAL_IDP_NAME.equals(authenticatorInfo
                        .get("idpName"))) {
                    LocalAuthenticatorConfig localAuthenticator = new LocalAuthenticatorConfig();
                    localAuthenticator.setName(authenticatorInfo
                            .get(ApplicationConstants.IDP_AUTHENTICATOR_NAME));
                    localAuthenticator.setDisplayName(authenticatorInfo
                            .get(ApplicationConstants.IDP_AUTHENTICATOR_DISPLAY_NAME));
                    stepLocalAuth.get(step).add(localAuthenticator);
                } else {
                    Map<String, List<FederatedAuthenticatorConfig>> stepFedIdps = stepFedIdPAuthenticators
                            .get(step);

                    if (!stepFedIdps.containsKey(authenticatorInfo
                            .get(ApplicationConstants.IDP_NAME))) {
                        stepFedIdps.put(authenticatorInfo.get(ApplicationConstants.IDP_NAME),
                                new ArrayList<FederatedAuthenticatorConfig>());
                    }

                    List<FederatedAuthenticatorConfig> idpAuths = stepFedIdps.get(authenticatorInfo
                            .get(ApplicationConstants.IDP_NAME));
                    FederatedAuthenticatorConfig fedAuthenticator = new FederatedAuthenticatorConfig();
                    fedAuthenticator.setName(authenticatorInfo
                            .get(ApplicationConstants.IDP_AUTHENTICATOR_NAME));
                    fedAuthenticator.setDisplayName(authenticatorInfo
                            .get(ApplicationConstants.IDP_AUTHENTICATOR_DISPLAY_NAME));
                    idpAuths.add(fedAuthenticator);
                }

                authStep.setSubjectStep("1".equals(stepInfoResultSet.getString(3)));
                authStep.setAttributeStep("1".equals(stepInfoResultSet.getString(4)));

                authSteps.put(step, authStep);
            }

            LocalAndOutboundAuthenticationConfig localAndOutboundConfiguration = new LocalAndOutboundAuthenticationConfig();
            AuthenticationStep[] authenticationSteps = new AuthenticationStep[authSteps.size()];

            int authStepCount = 0;

            for (Iterator<Entry<String, AuthenticationStep>> iterator = authSteps.entrySet()
                    .iterator(); iterator.hasNext(); ) {

                Entry<String, AuthenticationStep> entry = iterator.next();
                AuthenticationStep authStep = entry.getValue();
                String stepId = entry.getKey();

                List<LocalAuthenticatorConfig> localAuthenticatorList = stepLocalAuth.get(stepId);

                if (localAuthenticatorList != null && localAuthenticatorList.size() > 0) {
                    authStep.setLocalAuthenticatorConfigs(localAuthenticatorList
                            .toArray(new LocalAuthenticatorConfig[localAuthenticatorList.size()]));
                }

                Map<String, List<FederatedAuthenticatorConfig>> idpList = stepFedIdPAuthenticators
                        .get(stepId);

                if (idpList != null && idpList.size() > 0) {
                    IdentityProvider[] fedIdpList = new IdentityProvider[idpList.size()];
                    int idpCount = 0;

                    for (Iterator<Entry<String, List<FederatedAuthenticatorConfig>>> idpItr = idpList
                            .entrySet().iterator(); idpItr.hasNext(); ) {
                        Entry<String, List<FederatedAuthenticatorConfig>> idpEntry = idpItr.next();
                        String idpName = idpEntry.getKey();
                        List<FederatedAuthenticatorConfig> fedAuthenticators = idpEntry.getValue();
                        IdentityProvider idp = new IdentityProvider();
                        idp.setIdentityProviderName(idpName);
                        idp.setFederationHub(isFederationHubIdP(idpName, connection, tenantId));
                        idp.setFederatedAuthenticatorConfigs(fedAuthenticators
                                .toArray(new FederatedAuthenticatorConfig[fedAuthenticators.size()]));
                        idp.setDefaultAuthenticatorConfig(idp.getFederatedAuthenticatorConfigs()[0]);
                        fedIdpList[idpCount++] = idp;
                    }
                    authStep.setFederatedIdentityProviders(fedIdpList);
                }

                authenticationSteps[authStepCount++] = authStep;
            }

            Comparator<AuthenticationStep> comparator = new Comparator<AuthenticationStep>() {
                public int compare(AuthenticationStep step1, AuthenticationStep step2) {
                    return step1.getStepOrder() - step2.getStepOrder();
                }
            };

            Arrays.sort(authenticationSteps, comparator);

            localAndOutboundConfiguration.setAuthenticationSteps(authenticationSteps);

            String authType = getAuthenticationType(applicationId, connection);
            localAndOutboundConfiguration.setAuthenticationType(authType);

            PreparedStatement loadSendAuthListOfIdPs = null;
            ResultSet sendAuthListOfIdPsResultSet = null;

            try {
                loadSendAuthListOfIdPs = connection
                        .prepareStatement(ApplicationMgtDBQueries.LOAD_SEND_AUTH_LIST_OF_IDPS_BY_APP_ID);
                loadSendAuthListOfIdPs.setInt(1, tenantId);
                loadSendAuthListOfIdPs.setInt(2, applicationId);
                sendAuthListOfIdPsResultSet = loadSendAuthListOfIdPs.executeQuery();

                if (sendAuthListOfIdPsResultSet.next()) {
                    localAndOutboundConfiguration.setAlwaysSendBackAuthenticatedListOfIdPs("1"
                            .equals(sendAuthListOfIdPsResultSet.getString(1)));
                }
            } finally {
                IdentityApplicationManagementUtil.closeStatement(loadSendAuthListOfIdPs);
                IdentityApplicationManagementUtil.closeResultSet(sendAuthListOfIdPsResultSet);
            }

            PreparedStatement loadSubjectClaimUri = null;
            ResultSet subjectClaimUriResultSet = null;

            try {
                loadSubjectClaimUri = connection
                        .prepareStatement(ApplicationMgtDBQueries.LOAD_SUBJECT_CLAIM_URI_BY_APP_ID);
                loadSubjectClaimUri.setInt(1, tenantId);
                loadSubjectClaimUri.setInt(2, applicationId);
                subjectClaimUriResultSet = loadSubjectClaimUri.executeQuery();

                if (subjectClaimUriResultSet.next()) {
                    localAndOutboundConfiguration.setSubjectClaimUri(subjectClaimUriResultSet
                            .getString(1));
                }
            } finally {
                IdentityApplicationManagementUtil.closeStatement(loadSubjectClaimUri);
                IdentityApplicationManagementUtil.closeResultSet(subjectClaimUriResultSet);
            }

            return localAndOutboundConfiguration;
        } finally {
            IdentityApplicationManagementUtil.closeStatement(getStepInfoPrepStmt);
            IdentityApplicationManagementUtil.closeResultSet(stepInfoResultSet);
        }
    }

    private boolean isFederationHubIdP(String idPName, Connection connection, int tenantId)
            throws SQLException {

        PreparedStatement get = null;
        ResultSet resultSet = null;

        try {
            get = connection.prepareStatement(ApplicationMgtDBQueries.LOAD_HUB_IDP_BY_NAME);

            get.setString(1, CharacterEncoder.getSafeText(idPName));
            get.setInt(2, tenantId);
            resultSet = get.executeQuery();

            if (resultSet.next()) {
                return "1".equals(resultSet.getString(1));
            }

            return false;
        } finally {
            IdentityApplicationManagementUtil.closeStatement(get);
            IdentityApplicationManagementUtil.closeResultSet(resultSet);
        }

    }

    /**
     * @param applicationId
     * @param connection
     * @return
     * @throws IdentityApplicationManagementException
     */
    private ClaimConfig getClaimConfiguration(int applicationId, Connection connection, int tenantID)
            throws IdentityApplicationManagementException {

        ClaimConfig claimConfig = new ClaimConfig();
        ArrayList<ClaimMapping> claimMappingList = new ArrayList<ClaimMapping>();

        if (debugMode) {
            log.debug("Reading Claim Mappings of Application " + applicationId);
        }

        PreparedStatement get = null;
        ResultSet resultSet = null;
        try {
            get = connection.prepareStatement(ApplicationMgtDBQueries.LOAD_CLAIM_MAPPING_BY_APP_ID);
            // IDP_CLAIM, SP_CLAIM, IS_REQUESTED
            get.setInt(1, applicationId);
            get.setInt(2, tenantID);
            resultSet = get.executeQuery();

            while (resultSet.next()) {
                ClaimMapping claimMapping = new ClaimMapping();
                Claim localClaim = new Claim();
                Claim remoteClaim = new Claim();

                localClaim.setClaimUri(resultSet.getString(1));
                remoteClaim.setClaimUri(resultSet.getString(2));

                String requested = resultSet.getString(3);

                if ("1".equalsIgnoreCase(requested)) {
                    claimMapping.setRequested(true);
                } else {
                    claimMapping.setRequested(false);
                }

                if (remoteClaim.getClaimUri() == null
                        || remoteClaim.getClaimUri().trim().length() == 0) {
                    remoteClaim.setClaimUri(localClaim.getClaimUri());
                }

                if (localClaim.getClaimUri() == null
                        || localClaim.getClaimUri().trim().length() == 0) {
                    localClaim.setClaimUri(remoteClaim.getClaimUri());
                }

                claimMapping.setDefaultValue(resultSet.getString(4));

                claimMapping.setLocalClaim(localClaim);
                claimMapping.setRemoteClaim(remoteClaim);

                claimMappingList.add(claimMapping);

                if (debugMode) {
                    log.debug("Local Claim: " + claimMapping.getLocalClaim().getClaimUri()
                            + " SPClaim: " + claimMapping.getRemoteClaim().getClaimUri());
                }
            }

            claimConfig.setClaimMappings(claimMappingList.toArray(new ClaimMapping[claimMappingList
                    .size()]));
        } catch (SQLException e) {
            throw new IdentityApplicationManagementException(
                    "Error while retrieving all application");
        } finally {
            IdentityApplicationManagementUtil.closeStatement(get);
            IdentityApplicationManagementUtil.closeResultSet(resultSet);
        }

        PreparedStatement loadRoleClaim = null;
        ResultSet roleResultSet = null;

        try {
            loadRoleClaim = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_ROLE_CLAIM_BY_APP_ID);
            loadRoleClaim.setInt(1, tenantID);
            loadRoleClaim.setInt(2, applicationId);
            roleResultSet = loadRoleClaim.executeQuery();

            while (roleResultSet.next()) {
                claimConfig.setRoleClaimURI(roleResultSet.getString(1));
            }
        } catch (SQLException e) {
            throw new IdentityApplicationManagementException(
                    "Error while retrieving all application");
        } finally {
            IdentityApplicationManagementUtil.closeStatement(loadRoleClaim);
            IdentityApplicationManagementUtil.closeResultSet(roleResultSet);
        }

        PreparedStatement loadClaimDialect = null;
        ResultSet claimDialectResultSet = null;

        try {
            loadClaimDialect = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_CLAIM_DIALECT_BY_APP_ID);
            loadClaimDialect.setInt(1, tenantID);
            loadClaimDialect.setInt(2, applicationId);
            claimDialectResultSet = loadClaimDialect.executeQuery();

            if (claimDialectResultSet.next()) {
                claimConfig.setLocalClaimDialect("1".equals(claimDialectResultSet.getString(1)));
            }
        } catch (SQLException e) {
            throw new IdentityApplicationManagementException(
                    "Error while retrieving all application");
        } finally {
            IdentityApplicationManagementUtil.closeStatement(loadClaimDialect);
            IdentityApplicationManagementUtil.closeResultSet(claimDialectResultSet);
        }

        PreparedStatement loadSendLocalSubId = null;
        ResultSet sendLocalSubIdResultSet = null;

        try {
            loadSendLocalSubId = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_SEND_LOCAL_SUB_ID_BY_APP_ID);
            loadSendLocalSubId.setInt(1, tenantID);
            loadSendLocalSubId.setInt(2, applicationId);
            sendLocalSubIdResultSet = loadSendLocalSubId.executeQuery();

            if (sendLocalSubIdResultSet.next()) {
                claimConfig.setAlwaysSendMappedLocalSubjectId("1".equals(sendLocalSubIdResultSet
                        .getString(1)));
            }
        } catch (SQLException e) {
            throw new IdentityApplicationManagementException(
                    "Error while retrieving all application");
        } finally {
            IdentityApplicationManagementUtil.closeStatement(loadSendLocalSubId);
            IdentityApplicationManagementUtil.closeResultSet(sendLocalSubIdResultSet);
        }

        return claimConfig;
    }

    /**
     * @param applicationId
     * @param connection
     * @return
     * @throws IdentityApplicationManagementException
     */
    private RequestPathAuthenticatorConfig[] getRequestPathAuthenticators(int applicationId,
                                                                          Connection connection, int tenantID) throws IdentityApplicationManagementException {

        PreparedStatement loadReqPathAuthenticators = null;
        ResultSet authResultSet = null;
        List<RequestPathAuthenticatorConfig> authenticators = new ArrayList<RequestPathAuthenticatorConfig>();

        try {
            loadReqPathAuthenticators = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_REQ_PATH_AUTHENTICATORS_BY_APP_ID);
            loadReqPathAuthenticators.setInt(1, applicationId);
            loadReqPathAuthenticators.setInt(2, tenantID);
            authResultSet = loadReqPathAuthenticators.executeQuery();

            while (authResultSet.next()) {
                RequestPathAuthenticatorConfig reqAuth = new RequestPathAuthenticatorConfig();
                reqAuth.setName(authResultSet.getString(1));
                authenticators.add(reqAuth);
            }
        } catch (SQLException e) {
            throw new IdentityApplicationManagementException(
                    "Error while retrieving all application");
        } finally {
            IdentityApplicationManagementUtil.closeStatement(loadReqPathAuthenticators);
            IdentityApplicationManagementUtil.closeResultSet(authResultSet);
        }

        return authenticators.toArray(new RequestPathAuthenticatorConfig[authenticators.size()]);
    }

    /**
     * @param applicationId
     * @param authenticators
     * @param connection
     * @throws IdentityApplicationManagementException
     */
    private void updateRequestPathAuthenticators(int applicationId,
                                                 RequestPathAuthenticatorConfig[] authenticators, Connection connection)
            throws IdentityApplicationManagementException {

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        PreparedStatement storeReqPathAuthenticators = null;

        try {
            storeReqPathAuthenticators = connection
                    .prepareStatement(ApplicationMgtDBQueries.STORE_REQ_PATH_AUTHENTICATORS);
            if (authenticators != null && authenticators.length > 0) {
                for (RequestPathAuthenticatorConfig auth : authenticators) {
                    // TENANT_ID, AUTHENTICATOR_NAME, APP_ID
                    storeReqPathAuthenticators.setInt(1, tenantID);
                    storeReqPathAuthenticators.setString(2,
                            CharacterEncoder.getSafeText(auth.getName()));
                    storeReqPathAuthenticators.setInt(3, applicationId);
                    storeReqPathAuthenticators.addBatch();
                }
                storeReqPathAuthenticators.executeBatch();
            }
        } catch (SQLException e) {
            throw new IdentityApplicationManagementException(
                    "Error while retrieving all application");
        } finally {
            IdentityApplicationManagementUtil.closeStatement(storeReqPathAuthenticators);
        }
    }

    /**
     * @param applicationID
     * @param connection
     * @throws SQLException
     */
    private void deleteRequestPathAuthenticators(int applicationID, Connection connection)
            throws SQLException {

        if (debugMode) {
            log.debug("Deleting request path authenticators " + applicationID);
        }

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        PreparedStatement deleteReqAuthPrepStmt = null;
        try {
            deleteReqAuthPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.REMOVE_REQ_PATH_AUTHENTICATOR);
            deleteReqAuthPrepStmt.setInt(1, applicationID);
            deleteReqAuthPrepStmt.setInt(2, tenantID);
            deleteReqAuthPrepStmt.execute();

        } finally {
            IdentityApplicationManagementUtil.closeStatement(deleteReqAuthPrepStmt);
        }
    }

    /**
     * Reads the claim mappings for a given appID
     *
     * @param applicationId
     * @param connection
     * @return
     * @throws IdentityApplicationManagementException
     */
    private List<RoleMapping> getRoleMappingOfApplication(int applicationId, Connection connection,
                                                          int tenantID) throws IdentityApplicationManagementException {

        ArrayList<RoleMapping> roleMappingList = new ArrayList<RoleMapping>();

        if (debugMode) {
            log.debug("Reading Role Mapping of Application " + applicationId);
        }

        PreparedStatement getClientInfo = null;
        ResultSet resultSet = null;
        try {
            getClientInfo = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_ROLE_MAPPING_BY_APP_ID);
            // IDP_ROLE, SP_ROLE
            getClientInfo.setInt(1, applicationId);
            getClientInfo.setInt(2, tenantID);
            resultSet = getClientInfo.executeQuery();

            while (resultSet.next()) {
                RoleMapping roleMapping = new RoleMapping();
                LocalRole localRole = new LocalRole();
                localRole.setLocalRoleName(resultSet.getString(1));
                roleMapping.setLocalRole(localRole);
                roleMapping.setRemoteRole(resultSet.getString(2));
                roleMappingList.add(roleMapping);

                if (debugMode) {
                    log.debug("Local Role: " + roleMapping.getLocalRole().getLocalRoleName()
                            + " SPRole: " + roleMapping.getRemoteRole());
                }
            }

        } catch (SQLException e) {
            throw new IdentityApplicationManagementException(
                    "Error while retrieving all application");
        } finally {
            IdentityApplicationManagementUtil.closeStatement(getClientInfo);
            IdentityApplicationManagementUtil.closeResultSet(resultSet);
        }
        return roleMappingList;
    }

    /**
     * Get application Names for user
     *
     * @return
     * @throws IdentityApplicationManagementException
     */
    public ApplicationBasicInfo[] getAllApplicationBasicInfo()
            throws IdentityApplicationManagementException {

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        if (debugMode) {
            log.debug("Reading all Applications of Tenant " + tenantID);
        }

        Connection connection = null;
        PreparedStatement getAppNamesStmt = null;
        ResultSet appNameResultSet = null;

        ArrayList<ApplicationBasicInfo> appInfo = new ArrayList<ApplicationBasicInfo>();

        try {
            connection = JDBCPersistenceManager.getInstance().getDBConnection();
            getAppNamesStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_APP_NAMES_BY_TENANT);
            getAppNamesStmt.setInt(1, tenantID);
            appNameResultSet = getAppNamesStmt.executeQuery();

            while (appNameResultSet.next()) {
                ApplicationBasicInfo basicInfo = new ApplicationBasicInfo();
                if (ApplicationConstants.LOCAL_SP.equals(appNameResultSet.getString(1))) {
                    continue;
                }
                basicInfo.setApplicationName(appNameResultSet.getString(1));
                basicInfo.setDescription(appNameResultSet.getString(2));

                if (ApplicationMgtUtil.isUserAuthorized(basicInfo.getApplicationName())) {
                    appInfo.add(basicInfo);
                    if (debugMode) {
                        log.debug("Application Name:" + basicInfo.getApplicationName());
                    }
                }
            }
        } catch (SQLException e) {
            throw new IdentityApplicationManagementException("Error while Reading all Applications");
        } finally {
            IdentityApplicationManagementUtil.closeStatement(getAppNamesStmt);
            IdentityApplicationManagementUtil.closeResultSet(appNameResultSet);
            IdentityApplicationManagementUtil.closeConnection(connection);
        }

        return appInfo.toArray(new ApplicationBasicInfo[appInfo.size()]);
    }

    /**
     * Deletes the application from IDN_APPMGT_APP table. Cascade deletes with foreign key
     * constraints should delete the corresponding entries from the tables
     *
     * @param appName
     * @throws IdentityApplicationManagementException
     */
    public void deleteApplication(String appName) throws IdentityApplicationManagementException {

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        Connection connection = null;

        if (debugMode) {
            log.debug("Deleting Application " + appName);
        }

        // Now, delete the application
        PreparedStatement deleteClientPrepStmt = null;
        try {
            connection = JDBCPersistenceManager.getInstance().getDBConnection();
            // First, delete all the clients of the application
            int applicationID = getApplicationIDByName(appName, tenantID, connection);
            InboundAuthenticationConfig clients = getInboundAuthenticationConfig(applicationID,
                    connection, tenantID);
            for (InboundAuthenticationRequestConfig client : clients
                    .getInboundAuthenticationRequestConfigs()) {
                deleteClient(client.getInboundAuthKey(), client.getInboundAuthType());
            }

            deleteClientPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.REMOVE_APP_FROM_APPMGT_APP);
            deleteClientPrepStmt.setString(1, CharacterEncoder.getSafeText(appName));
            deleteClientPrepStmt.setInt(2, tenantID);
            deleteClientPrepStmt.execute();

            if (!connection.getAutoCommit()) {
                connection.commit();
            }

        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            throw new IdentityApplicationManagementException("Error deleting application");
        } finally {
            IdentityApplicationManagementUtil.closeStatement(deleteClientPrepStmt);
            IdentityApplicationManagementUtil.closeConnection(connection);
        }
    }

    /**
     * Deletes the Application with application ID
     *
     * @param applicationID
     * @param connection
     * @throws IdentityApplicationManagementException
     */
    public void deleteApplication(int applicationID, Connection connection)
            throws IdentityApplicationManagementException {

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        if (debugMode) {
            log.debug("Deleting Application " + applicationID);
        }

        // Now, delete the application
        PreparedStatement deleteClientPrepStmt = null;
        try {

            // delete clients
            InboundAuthenticationConfig clients = getInboundAuthenticationConfig(applicationID,
                    connection, tenantID);
            for (InboundAuthenticationRequestConfig client : clients
                    .getInboundAuthenticationRequestConfigs()) {
                deleteClient(client.getInboundAuthKey(), client.getInboundAuthType());
            }

            String applicationName = getApplicationName(applicationID, connection);
            // delete roles
            ApplicationMgtUtil.deleteAppRole(applicationName);

            deleteClientPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.REMOVE_APP_FROM_APPMGT_APP_WITH_ID);
            deleteClientPrepStmt.setInt(1, applicationID);
            deleteClientPrepStmt.setInt(2, tenantID);
            deleteClientPrepStmt.execute();

            if (!connection.getAutoCommit()) {
                connection.commit();
            }

        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            throw new IdentityApplicationManagementException("Error deleting application");

        } finally {
            IdentityApplicationManagementUtil.closeStatement(deleteClientPrepStmt);
        }

    }

    /**
     * Deleting Clients of the Application
     *
     * @param applicationID
     * @param connection
     * @throws IdentityApplicationManagementException
     */
    private void deleteInboundAuthRequestConfiguration(int applicationID, Connection connection)
            throws SQLException {

        if (debugMode) {
            log.debug("Deleting Clients of the Application " + applicationID);
        }

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        PreparedStatement deleteClientPrepStmt = null;

        try {
            deleteClientPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.REMOVE_CLIENT_FROM_APPMGT_CLIENT);
            // APP_ID = ? AND TENANT_ID = ?
            deleteClientPrepStmt.setInt(1, applicationID);
            deleteClientPrepStmt.setInt(2, tenantID);
            deleteClientPrepStmt.execute();
        } finally {
            IdentityApplicationManagementUtil.closeStatement(deleteClientPrepStmt);
        }
    }

    /**
     * @param applicationId
     * @param connection
     * @throws SQLException
     */
    private void deleteLocalAndOutboundAuthenticationConfiguration(int applicationId,
                                                                   Connection connection) throws SQLException {

        if (debugMode) {
            log.debug("Deleting Steps of Application " + applicationId);
        }

        PreparedStatement deleteLocalAndOutboundAuthConfigPrepStmt = null;
        int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        try {
            deleteLocalAndOutboundAuthConfigPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.REMOVE_STEP_FROM_APPMGT_STEP);
            deleteLocalAndOutboundAuthConfigPrepStmt.setInt(1, applicationId);
            deleteLocalAndOutboundAuthConfigPrepStmt.setInt(2, tenantId);
            deleteLocalAndOutboundAuthConfigPrepStmt.execute();

        } finally {
            IdentityApplicationManagementUtil
                    .closeStatement(deleteLocalAndOutboundAuthConfigPrepStmt);
        }
    }

    /**
     * @param applicationId
     * @param connection
     * @throws SQLException
     */
    private void deleteOutboundProvisioningConfiguration(int applicationId, Connection connection)
            throws SQLException {

        if (debugMode) {
            log.debug("Deleting Steps of Application " + applicationId);
        }

        PreparedStatement deleteOutboundProConfigPrepStmt = null;
        int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        try {
            deleteOutboundProConfigPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.REMOVE_PRO_CONNECTORS);
            deleteOutboundProConfigPrepStmt.setInt(1, applicationId);
            deleteOutboundProConfigPrepStmt.setInt(2, tenantId);
            deleteOutboundProConfigPrepStmt.execute();

        } finally {
            IdentityApplicationManagementUtil.closeStatement(deleteOutboundProConfigPrepStmt);
        }
    }

    /**
     * Deletes clients using the service stubs
     *
     * @param clientIdentifier
     * @param type
     * @throws IdentityApplicationManagementException
     */
    private void deleteClient(String clientIdentifier, String type)
            throws IdentityApplicationManagementException {
        if ("samlsso".equalsIgnoreCase(type)) {
            new SAMLApplicationDAOImpl().removeServiceProviderConfiguration(clientIdentifier);
        } else if ("oauth2".equalsIgnoreCase(type)) {
            new OAuthApplicationDAOImpl().removeOAuthApplication(clientIdentifier);
        }
    }

    /**
     * Delete Claim Mapping of the Application
     *
     * @param applicationID
     * @param connection
     * @throws IdentityApplicationManagementException
     */
    private void deteClaimConfiguration(int applicationID, Connection connection)
            throws SQLException {

        if (debugMode) {
            log.debug("Deleting Application Claim Mapping " + applicationID);
        }

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        PreparedStatement deleteCliamPrepStmt = null;
        try {
            deleteCliamPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.REMOVE_CLAIM_MAPPINGS_FROM_APPMGT_CLAIM_MAPPING);
            deleteCliamPrepStmt.setInt(1, applicationID);
            deleteCliamPrepStmt.setInt(2, tenantID);
            deleteCliamPrepStmt.execute();

        } finally {
            IdentityApplicationManagementUtil.closeStatement(deleteCliamPrepStmt);
        }
    }

    /**
     * @param applicationID
     * @param connection
     * @throws IdentityApplicationManagementException
     */
    public void deletePermissionAndRoleConfiguration(int applicationID, Connection connection)
            throws SQLException {

        int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        if (debugMode) {
            log.debug("Deleting Role Mapping of Application " + applicationID);
        }

        PreparedStatement deleteRoleMappingPrepStmt = null;
        try {
            deleteRoleMappingPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.REMOVE_ROLE_MAPPINGS_FROM_APPMGT_ROLE_MAPPING);
            deleteRoleMappingPrepStmt.setInt(1, applicationID);
            deleteRoleMappingPrepStmt.setInt(2, tenantID);
            deleteRoleMappingPrepStmt.execute();
        } finally {
            IdentityApplicationManagementUtil.closeStatement(deleteRoleMappingPrepStmt);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.wso2.carbon.identity.application.mgt.dao.ApplicationDAO#getServiceProviderNameByClientId
     * (java.lang.String, java.lang.String, java.lang.String)
     */
    public String getServiceProviderNameByClientId(String clientId, String clientType,
                                                   String tenantDomain) throws IdentityApplicationManagementException {
        int tenantID = -123;

        if (tenantDomain != null) {
            try {
                tenantID = ApplicationManagementServiceComponentHolder.getRealmService()
                        .getTenantManager().getTenantId(tenantDomain);
            } catch (UserStoreException e1) {
                throw new IdentityApplicationManagementException("Error while reading application");
            }
        }

        String applicationName = null;

        // Reading application name from the database
        Connection connection = null;
        PreparedStatement storeAppPrepStmt = null;
        ResultSet appNameResult = null;
        try {
            connection = JDBCPersistenceManager.getInstance().getDBConnection();
            storeAppPrepStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_APPLICATION_NAME_BY_CLIENT_ID_AND_TYPE);
            storeAppPrepStmt.setString(1, CharacterEncoder.getSafeText(clientId));
            storeAppPrepStmt.setString(2, CharacterEncoder.getSafeText(clientType));
            storeAppPrepStmt.setInt(3, tenantID);
            storeAppPrepStmt.setInt(4, tenantID);
            appNameResult = storeAppPrepStmt.executeQuery();
            if (appNameResult.next()) {
                applicationName = appNameResult.getString(1);
            }
        } catch (SQLException e) {
            throw new IdentityApplicationManagementException("Error while reading application");
        } finally {
            IdentityApplicationManagementUtil.closeResultSet(appNameResult);
            IdentityApplicationManagementUtil.closeStatement(storeAppPrepStmt);
            IdentityApplicationManagementUtil.closeConnection(connection);
        }

        return applicationName;
    }

    /**
     * @param serviceProviderName
     * @param tenantDomain
     * @param localIdpAsKey
     * @return
     * @throws SQLException
     * @throws IdentityApplicationManagementException
     */
    private Map<String, String> getClaimMapping(String serviceProviderName, String tenantDomain,
                                                boolean localIdpAsKey) throws SQLException, IdentityApplicationManagementException {

        int tenantID = -123;

        if (tenantDomain != null) {
            try {
                tenantID = ApplicationManagementServiceComponentHolder.getRealmService()
                        .getTenantManager().getTenantId(tenantDomain);
            } catch (UserStoreException e1) {
                throw new IdentityApplicationManagementException("Error while reading application");
            }
        }
        Map<String, String> claimMapping = new HashMap<String, String>();

        if (debugMode) {
            log.debug("Reading Claim Mappings of Application " + serviceProviderName);
        }

        PreparedStatement getClaimPreStmt = null;
        ResultSet resultSet = null;
        Connection connection = null;
        try {

            connection = JDBCPersistenceManager.getInstance().getDBConnection();
            getClaimPreStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_CLAIM_MAPPING_BY_APP_NAME);
            // IDP_CLAIM, SP_CLAIM, IS_REQUESTED
            getClaimPreStmt.setString(1, CharacterEncoder.getSafeText(serviceProviderName));
            getClaimPreStmt.setInt(2, tenantID);
            resultSet = getClaimPreStmt.executeQuery();

            while (resultSet.next()) {
                if (localIdpAsKey) {
                    claimMapping.put(resultSet.getString(1), resultSet.getString(2));
                } else {
                    claimMapping.put(resultSet.getString(2), resultSet.getString(1));
                }
            }

        } finally {
            IdentityApplicationManagementUtil.closeStatement(getClaimPreStmt);
            IdentityApplicationManagementUtil.closeResultSet(resultSet);
            IdentityApplicationManagementUtil.closeConnection(connection);
        }

        return claimMapping;
    }

    @Override
    public Map<String, String> getServiceProviderToLocalIdPClaimMapping(String serviceProviderName,
                                                                        String tenantDomain) throws IdentityApplicationManagementException {
        try {
            return getClaimMapping(serviceProviderName, tenantDomain, false);
        } catch (SQLException e) {
            throw new IdentityApplicationManagementException(
                    "Error while retrieving claim mapping", e);
        }
    }

    @Override
    public Map<String, String> getLocalIdPToServiceProviderClaimMapping(String serviceProviderName,
                                                                        String tenantDomain) throws IdentityApplicationManagementException {
        try {
            return getClaimMapping(serviceProviderName, tenantDomain, true);
        } catch (SQLException e) {
            throw new IdentityApplicationManagementException(
                    "Error while retrieving claim mapping", e);
        }
    }

    @Override
    public List<String> getAllRequestedClaimsByServiceProvider(String serviceProviderName,
                                                               String tenantDomain) throws IdentityApplicationManagementException {
        int tenantID = -123;

        if (tenantDomain != null) {
            try {
                tenantID = ApplicationManagementServiceComponentHolder.getRealmService()
                        .getTenantManager().getTenantId(tenantDomain);
            } catch (UserStoreException e1) {
                throw new IdentityApplicationManagementException("Error while reading application");
            }
        }
        List<String> reqClaimUris = new ArrayList<String>();

        if (debugMode) {
            log.debug("Reading Claim Mappings of Application " + serviceProviderName);
        }

        PreparedStatement getClaimPreStmt = null;
        ResultSet resultSet = null;
        Connection connection = null;
        try {

            connection = JDBCPersistenceManager.getInstance().getDBConnection();

            getClaimPreStmt = connection
                    .prepareStatement(ApplicationMgtDBQueries.LOAD_CLAIM_MAPPING_BY_APP_NAME);

            // IDP_CLAIM, SP_CLAIM, IS_REQUESTED
            getClaimPreStmt.setString(1, CharacterEncoder.getSafeText(serviceProviderName));
            getClaimPreStmt.setInt(2, tenantID);
            resultSet = getClaimPreStmt.executeQuery();

            while (resultSet.next()) {
                if ("1".equalsIgnoreCase(resultSet.getString(3))) {
                    reqClaimUris.add(resultSet.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IdentityApplicationManagementException(
                    "Error while retrieving requested claims", e);
        } finally {
            IdentityApplicationManagementUtil.closeStatement(getClaimPreStmt);
            IdentityApplicationManagementUtil.closeResultSet(resultSet);
            IdentityApplicationManagementUtil.closeConnection(connection);
        }
        return reqClaimUris;

    }

    /**
     * @param conn
     * @param tenantId
     * @param idpName
     * @param authenticatorName
     * @return
     * @throws SQLException
     */
    private int getAuthentictorID(Connection conn, int tenantId, String idpName,
                                  String authenticatorName) throws SQLException {
        if (idpName == null || idpName.isEmpty()) {
            return -1;
        }
        int authId = -1;

        PreparedStatement prepStmt = null;
        ResultSet rs = null;
        String sqlStmt = ApplicationMgtDBQueries.LOAD_IDP_AUTHENTICATOR_ID;
        try {
            prepStmt = conn.prepareStatement(sqlStmt);
            prepStmt.setString(1, CharacterEncoder.getSafeText(authenticatorName));
            prepStmt.setString(2, CharacterEncoder.getSafeText(idpName));
            prepStmt.setInt(3, tenantId);
            prepStmt.setInt(4, tenantId);
            prepStmt.setInt(5, MultitenantConstants.SUPER_TENANT_ID);
            rs = prepStmt.executeQuery();
            if (rs.next()) {
                authId = rs.getInt(1);
            }
        } finally {
            IdentityApplicationManagementUtil.closeStatement(prepStmt);
        }
        return authId;
    }

    /**
     * @param conn
     * @param tenantId
     * @param authenticatorId
     * @return
     * @throws SQLException
     */
    private Map<String, String> getAuthenticatorInfo(Connection conn, int tenantId,
                                                     int authenticatorId) throws SQLException {
        PreparedStatement prepStmt = null;
        ResultSet rs = null;
        String sqlStmt = ApplicationMgtDBQueries.LOAD_IDP_AND_AUTHENTICATOR_NAMES;
        Map<String, String> returnData = new HashMap<String, String>();
        try {
            prepStmt = conn.prepareStatement(sqlStmt);
            prepStmt.setInt(1, authenticatorId);
            prepStmt.setInt(2, tenantId);
            prepStmt.setInt(3, tenantId);
            prepStmt.setInt(4, MultitenantConstants.SUPER_TENANT_ID);
            prepStmt.setInt(5, MultitenantConstants.SUPER_TENANT_ID);
            rs = prepStmt.executeQuery();
            while (rs.next()) {
                returnData.put(ApplicationConstants.IDP_NAME, rs.getString(1));
                returnData.put(ApplicationConstants.IDP_AUTHENTICATOR_NAME, rs.getString(2));
                returnData
                        .put(ApplicationConstants.IDP_AUTHENTICATOR_DISPLAY_NAME, rs.getString(3));
            }
        } finally {
            IdentityApplicationManagementUtil.closeStatement(prepStmt);
        }
        return returnData;
    }

    /**
     * @param conn
     * @param tenantId
     * @param idpName
     * @param authenticatorName
     * @param authenticatorDispalyName
     * @return
     * @throws SQLException
     */
    private int addAuthenticator(Connection conn, int tenantId, String idpName,
                                 String authenticatorName, String authenticatorDispalyName) throws SQLException {
        int authenticatorId = -1;
        PreparedStatement prepStmt = null;
        ResultSet rs = null;
        // TENANT_ID, IDP_ID, NAME,IS_ENABLED, DISPLAY_NAME
        String sqlStmt = ApplicationMgtDBQueries.STORE_LOCAL_AUTHENTICATOR;
        try {
            String dbProductName = conn.getMetaData().getDatabaseProductName();
            prepStmt = conn.prepareStatement(sqlStmt, new String[]{
                    DBUtils.getConvertedAutoGeneratedColumnName(dbProductName, "ID")});
            prepStmt.setInt(1, tenantId);
            prepStmt.setString(2, CharacterEncoder.getSafeText(idpName));
            prepStmt.setInt(3, tenantId);
            prepStmt.setString(4, CharacterEncoder.getSafeText(authenticatorName));
            prepStmt.setString(5, "1");
            prepStmt.setString(6, CharacterEncoder.getSafeText(authenticatorDispalyName));
            prepStmt.execute();
            rs = prepStmt.getGeneratedKeys();
            if (rs.next()) {
                authenticatorId = rs.getInt(1);
            }
        } finally {
            IdentityApplicationManagementUtil.closeStatement(prepStmt);
        }
        return authenticatorId;
    }

}
