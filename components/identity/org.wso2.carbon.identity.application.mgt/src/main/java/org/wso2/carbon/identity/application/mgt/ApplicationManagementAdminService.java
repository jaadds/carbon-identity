/*
 * Copyright (c) WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 * 
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.application.mgt;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.core.AbstractAdmin;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.*;

/**
 * Application management admin service
 */
public class ApplicationManagementAdminService extends AbstractAdmin {

    private static Log log = LogFactory.getLog(ApplicationManagementAdminService.class);
    private ApplicationManagementService applicationMgtService;

    /**
     * Creates a service provider with basic information.First we need to create
     * a role with the
     * application name. Only the users in this role will be able to edit/update
     * the application.The
     * user will assigned to the created role.Internal roles used.
     *
     * @param serviceProvider Service provider
     * @return application id
     * @throws org.wso2.carbon.identity.application.common.IdentityApplicationManagementException
     */
    public int createApplication(ServiceProvider serviceProvider)
            throws IdentityApplicationManagementException {
        applicationMgtService = ApplicationManagementServiceImpl.getInstance();
        return applicationMgtService.createApplication(serviceProvider);
    }

    /**
     * Get Service provider information for given application name
     *
     * @param applicationName Application name
     * @return service provider
     * @throws org.wso2.carbon.identity.application.common.IdentityApplicationManagementException
     */
    public ServiceProvider getApplication(String applicationName)
            throws IdentityApplicationManagementException {

        if (!ApplicationConstants.LOCAL_SP.equals(applicationName) &&
                !ApplicationMgtUtil.isUserAuthorized(applicationName)) {
            log.warn("Illegal Access! User " + CarbonContext.getThreadLocalCarbonContext().getUsername() +
                    " does not have access to the application " + applicationName);
            throw new IdentityApplicationManagementException("User not authorized");
        }
        applicationMgtService = ApplicationManagementServiceImpl.getInstance();
        return applicationMgtService.getApplication(applicationName);
    }

    /**
     * Get all basic application information
     *
     * @return Application Basic information array
     * @throws org.wso2.carbon.identity.application.common.IdentityApplicationManagementException
     */
    public ApplicationBasicInfo[] getAllApplicationBasicInfo()
            throws IdentityApplicationManagementException {
        applicationMgtService = ApplicationManagementServiceImpl.getInstance();
        return applicationMgtService.getAllApplicationBasicInfo();
    }

    /**
     * Update application
     *
     * @param serviceProvider Service provider
     * @throws org.wso2.carbon.identity.application.common.IdentityApplicationManagementException
     */
    public void updateApplication(ServiceProvider serviceProvider)
            throws IdentityApplicationManagementException {
        // check whether use is authorized to update the application.
        if (!ApplicationConstants.LOCAL_SP.equals(serviceProvider.getApplicationName()) &&
                !ApplicationMgtUtil.isUserAuthorized(serviceProvider.getApplicationName(),
                        serviceProvider.getApplicationID())) {
            log.warn("Illegal Access! User " + CarbonContext.getThreadLocalCarbonContext().getUsername() +
                    " does not have access to the application " +
                    serviceProvider.getApplicationName());
            throw new IdentityApplicationManagementException("User not authorized");
        }
        applicationMgtService = ApplicationManagementServiceImpl.getInstance();
        applicationMgtService.updateApplication(serviceProvider);
    }

    /**
     * Delete Application
     *
     * @param applicationName Application name
     * @throws org.wso2.carbon.identity.application.common.IdentityApplicationManagementException
     */
    public void deleteApplication(String applicationName)
            throws IdentityApplicationManagementException {

        if (!ApplicationMgtUtil.isUserAuthorized(applicationName)) {
            log.warn("Illegal Access! User " + CarbonContext.getThreadLocalCarbonContext().getUsername() +
                    " does not have access to the application " + applicationName);
            throw new IdentityApplicationManagementException("User not authorized");
        }
        applicationMgtService = ApplicationManagementServiceImpl.getInstance();
        applicationMgtService.deleteApplication(applicationName);
    }

    /**
     * Get identity provider by identity provider name
     *
     * @param federatedIdPName Federated identity provider name
     * @return Identity provider
     * @throws org.wso2.carbon.identity.application.common.IdentityApplicationManagementException
     */
    public IdentityProvider getIdentityProvider(String federatedIdPName)
            throws IdentityApplicationManagementException {
        applicationMgtService = ApplicationManagementServiceImpl.getInstance();
        return applicationMgtService.getIdentityProvider(federatedIdPName);
    }

    /**
     * Get all identity providers
     *
     * @return Identity providers array
     * @throws org.wso2.carbon.identity.application.common.IdentityApplicationManagementException
     */
    public IdentityProvider[] getAllIdentityProviders()
            throws IdentityApplicationManagementException {
        applicationMgtService = ApplicationManagementServiceImpl.getInstance();
        return applicationMgtService.getAllIdentityProviders();
    }

    /**
     * Get all local authenticators
     *
     * @return local authenticators array
     * @throws org.wso2.carbon.identity.application.common.IdentityApplicationManagementException
     */
    public LocalAuthenticatorConfig[] getAllLocalAuthenticators()
            throws IdentityApplicationManagementException {
        applicationMgtService = ApplicationManagementServiceImpl.getInstance();
        return applicationMgtService.getAllLocalAuthenticators();
    }

    /**
     * Get all request path authenticator config
     *
     * @return Request path authenticator config array
     * @throws org.wso2.carbon.identity.application.common.IdentityApplicationManagementException
     */
    public RequestPathAuthenticatorConfig[] getAllRequestPathAuthenticators()
            throws IdentityApplicationManagementException {
        applicationMgtService = ApplicationManagementServiceImpl.getInstance();
        return applicationMgtService.getAllRequestPathAuthenticators();
    }

    /**
     * Get all local claim uris
     *
     * @return claim uri array
     * @throws org.wso2.carbon.identity.application.common.IdentityApplicationManagementException
     */
    public String[] getAllLocalClaimUris() throws IdentityApplicationManagementException {
        applicationMgtService = ApplicationManagementServiceImpl.getInstance();
        return applicationMgtService.getAllLocalClaimUris();
    }

}