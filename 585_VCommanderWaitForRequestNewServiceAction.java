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

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.embotics.vlm.plugin.Messages;
import com.embotics.vlm.plugin.VCommanderAction;
import com.embotics.vlm.plugin.VCommanderConfig;
import com.embotics.vlm.rest.v30.client.VCommanderClient;
import com.embotics.vlm.rest.v30.client.model.VCommanderException;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;

/**
 * A vCommander Action, which should be used in pair with VCommanderRequestNewServiceAction
 * If that action was submitted asynchronously, this step should be used to wait for the results 
 * 
 * @author btarczali
 */
public class VCommanderWaitForRequestNewServiceAction extends AbstractVCommanderAction {

	private final String requestId;


	@DataBoundConstructor
	public VCommanderWaitForRequestNewServiceAction(String requestId, Long timeout, Long polling) {
		super(true, timeout, polling);
		this.requestId = requestId;
	}

	public String getRequestId() {
		return requestId;
	}

	@Override
	public void perform(Run<?, ?> run, TaskListener listener) throws InterruptedException, IOException {
		String requestIdStr = requestId;
		if(requestIdStr != null) {
			requestIdStr = Util.replaceMacro(requestIdStr, run.getEnvironment(listener));
		}
		Long numericRequestId = null;
		if(StringUtils.isNotBlank(requestIdStr)) {
			try {
				numericRequestId = Long.valueOf(requestIdStr);
			} catch (NumberFormatException e) {
				listener.getLogger().println(e);
			}
		}
		if(numericRequestId==null) {
			throw new VCommanderException("Error in build step configuration. RequestId is not specified.");
		}

		
		VCommanderClient client = VCommanderConfig.getVCommanderClient();
		listener.getLogger().println(client.getClientInfo());

		try {
			VCommanderRequestNewServiceAction.waitForServiceRequestToBeCompleted(client, numericRequestId, getTimeout(), getPolling(), run, listener);
		} finally {
			client.close();
		}
	}

	
	@Extension(ordinal=165) // This is displayed below VCommanderRequestNewServiceAction
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    
	@Override
    public Descriptor<VCommanderAction> getDescriptor() {
        return DESCRIPTOR;
    }
	
	public static final class DescriptorImpl extends AbstractVCommanderActionDescriptor {

		@Override
		public String getDisplayName() {
			return Messages.VCommanderWaitForRequestNewServiceAction_DisplayName();
		}
		
		public String getDefaultRequestId() {
			return "$" + VCommanderRequestNewServiceAction.ENV_VARIABLE_REQUEST_ID;
		}

		
		////////////////////
		// form validations

		
		/**
		 * Called by jelly, to validate requestId field
		 */
		public FormValidation doCheckRequestId(@QueryParameter String requestId) throws IOException, ServletException {
			if(PluginUtils.isNumericOrVariable(requestId)) {
				return FormValidation.ok();
			} else {
				return FormValidation.error(Messages.VCommanderWaitForRequestNewServiceAction_errors_requestId());
			}
		}

	}

}
