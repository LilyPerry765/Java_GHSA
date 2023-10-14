/*************************************************************************
 *
 *	Copyright (c) 2017 Embotics Corporation. All Rights Reserved.
 *
 *	No part of this software may be reproduced, used in any
 *	information storage and retrieval system, or transmitted in
 *	any form or by any means, electronic, mechanical or otherwise,
 *	without the written permission of Embotics Corporation.
 *
 ***************************************************************************/

package com.embotics.vlm.plugin.actions;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.bind.JavaScriptMethod;

import com.embotics.vlm.plugin.Messages;
import com.embotics.vlm.plugin.VCommanderAction;
import com.embotics.vlm.plugin.VCommanderConfig;
import com.embotics.vlm.rest.v30.client.VCommanderClient;
import com.embotics.vlm.rest.v30.client.model.DeployedComponentInfo;
import com.embotics.vlm.rest.v30.client.model.VCommanderException;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;

/**
 * A vCommander Action, which submits a new service request
 * 
 * @author btarczali
 */
public class VCommanderRequestNewServiceAction extends AbstractVCommanderAction {
	static final String ENV_VARIABLE_REQUEST_ID = "VCOMMANDER_REQUESTED_SERVICE_ID";
	static final String VCOMMANDER_REQUESTED_SERVICE_COMPONENT_NAME_FORMAT = "VCOMMANDER_REQUESTED_SERVICE%d_COMPONENT%d_NAME";
	static final String VCOMMANDER_REQUESTED_SERVICE_COMPONENT_TYPE_FORMAT = "VCOMMANDER_REQUESTED_SERVICE%d_COMPONENT%d_TYPE";

	private final String serviceName;
	private final String payload;
	
	
	@DataBoundConstructor
	public VCommanderRequestNewServiceAction(String payload, Boolean sync, Long timeout, Long polling) {
		super(sync, timeout, polling);
		this.payload = payload;
		this.serviceName = getDefaultServiceName(payload);

	}

	public String getServiceName() {
		return serviceName;
	}

	public String getPayload() {
		return payload;
	}

	private String getDefaultServiceName(String payload) {
		VCommanderClient client = null;

		try {
			client = VCommanderConfig.getVCommanderClient();
			
			// Retrieve published service name
			return client.getPublishedServiceNameByPayload(payload);
		} catch (Exception e) {
			// ignoring all errors; service name is used only for display / lookup purposes
			return StringUtils.EMPTY;
		} finally {
			if(client!=null) {
				client.close();
			}
		}
	}

	@Override
	public void perform(Run<?, ?> run, TaskListener listener) throws InterruptedException, IOException {
		// check if user configured a service request
		if (StringUtils.isBlank(payload)) {
			throw new VCommanderException("There is no configuration for the Commander service request build step.");
		}
		try {
			new JSONObject(payload);
		} catch (JSONException e) {
			throw new VCommanderException(e, "Error in build step configuration. Payload is not in valid JSON format.");
		}

		VCommanderClient client = VCommanderConfig.getVCommanderClient();
		listener.getLogger().println(client.getClientInfo());

		try {
			Long requestId;
			try {
				// request service
				String resolvedPayload = Util.replaceMacro(payload, run.getEnvironment(listener));
				listener.getLogger().println("Creating new service request with payload: " + resolvedPayload);
				requestId = client.requestService(resolvedPayload);
				
				//create environment variables for the results
				PluginUtils.addEnvVariable(run, listener, ENV_VARIABLE_REQUEST_ID, requestId.toString());
				listener.getLogger().println("Service was succesfuly requested. RequestID: " + requestId);
			} catch (JSONException e) {
				throw new VCommanderException(e, "Error while requesting service request from Commander.");
			}
	
			if(getSync()) {
				waitForServiceRequestToBeCompleted(client, requestId, getTimeout(), getPolling(), run, listener);
			}

		} finally {
			client.close();
		}
	}
	
	static void waitForServiceRequestToBeCompleted(VCommanderClient client, Long requestId, long timeout, long polling, Run<?, ?> run, TaskListener listener) throws VCommanderException, InterruptedException {
		try {
			// monitor request until is completed or failed
			listener.getLogger().println("Waiting " + timeout + " minutes for request completion. Checking every " + polling + " seconds ...");
			List<DeployedComponentInfo> deployedComponents = client.waitForServiceRequestToBeCompleted(requestId, timeout, polling, listener.getLogger());
			listener.getLogger().println("Service request successfully completed.");

			//create environment variables for the results
			for (DeployedComponentInfo deployedComponent : deployedComponents) {
				String nameEnvVarKey = String.format(VCOMMANDER_REQUESTED_SERVICE_COMPONENT_NAME_FORMAT, deployedComponent.getServiceIndex(), deployedComponent.getComponentIndex());
				PluginUtils.addEnvVariable(run, listener, nameEnvVarKey, deployedComponent.getComponentName());
				
				String typeEnvVarKey = String.format(VCOMMANDER_REQUESTED_SERVICE_COMPONENT_TYPE_FORMAT, deployedComponent.getServiceIndex(), deployedComponent.getComponentIndex());
				PluginUtils.addEnvVariable(run, listener, typeEnvVarKey, deployedComponent.getComponentType());
			}

		} catch (JSONException e) {
			throw new VCommanderException(e, "Error while waiting for request completion.");
		}
	}

	
	@Extension(ordinal=166) // This is displayed at the top as the default
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    
	@Override
    public Descriptor<VCommanderAction> getDescriptor() {
        return DESCRIPTOR;
    }
	
	public static final class DescriptorImpl extends AbstractVCommanderActionDescriptor {

		@Override
		public String getDisplayName() {
			return Messages.VCommanderRequestNewServiceAction_displayName();
		}
    	
		/**
		 * Called from the jelly form, to retrieve the payload based on published service name
		 * @param serviceName		the publised service name
		 * @return					the payload json string
		 * 
		 * @throws IOException
		 * @throws ServletException
		 */
		@JavaScriptMethod
		public String getPayload(String serviceName) throws IOException, ServletException {
			VCommanderClient client = VCommanderConfig.getVCommanderClient();
			try {
				Long publishedServiceId = client.getPublishedService(serviceName);
				return client.getServiceRequestPayload(publishedServiceId);
			} catch (Exception e) {
				if(e.getCause() !=null && e.getCause() instanceof ConnectException) {
					return Messages.VCommanderConfig_connection_failedConnection();
				}
				return "Error: " + e.getMessage();
			} finally {
				client.close();
			}
		}

		////////////////////
		// form validations

		/**
		 * Called by jelly, to validate payload field
		 */
		public FormValidation doCheckPayload(@QueryParameter String payload) throws IOException, ServletException {
			// if no content, do not return error;
			// we do not want to show the initial form with error
			if (StringUtils.isBlank(payload)) {
				return FormValidation.ok();
			}
			try {
				new JSONObject(payload);
				return FormValidation.ok();
			} catch (JSONException e) {
				return FormValidation.error(Messages.VCommanderRequestNewServiceAction_errors_payloadFormat(e.getMessage()));
			}
		}
	}

}
