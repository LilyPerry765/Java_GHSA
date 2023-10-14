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
 * A vCommander Action, which should be used in pair with VCommanderRunWorkflowAction
 * If that action was submitted asynchronously, this step should be used to wait for the results 
 * 
 * @author btarczali
 */
public class VCommanderWaitForRunWorkflowAction extends AbstractVCommanderAction {
	
	private final String taskId;
	

	@DataBoundConstructor
	public VCommanderWaitForRunWorkflowAction(String taskId, Long timeout, Long polling) {
		super(true, timeout, polling);
		this.taskId = taskId;
	}

	public String getTaskId() {
		return taskId;
	}

	@Override
	public void perform(Run<?, ?> run, TaskListener listener) throws InterruptedException, IOException {
		String taskIdStr = taskId;
		if(taskIdStr != null) {
			taskIdStr = Util.replaceMacro(taskIdStr, run.getEnvironment(listener));
		}
		if(StringUtils.isBlank(taskIdStr)) {
			throw new VCommanderException("Error in build step configuration. TaskId is not specified.");
		}

		
		VCommanderClient client = VCommanderConfig.getVCommanderClient();
		listener.getLogger().println(client.getClientInfo());
		
		try {
			VCommanderRunWorkflowAction.waitForWorkflowToBeCompleted(client, taskIdStr, getTimeout(), getPolling(), run, listener);
		} finally {
			client.close();
		}
	}


	@Extension(ordinal=155)
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    
    public Descriptor<VCommanderAction> getDescriptor() {
        return DESCRIPTOR;
    }
	
	public static final class DescriptorImpl extends AbstractVCommanderActionDescriptor {

		@Override
		public String getDisplayName() {
			return Messages.VCommanderWaitForRunWorkflowAction_DisplayName();
		}
    	
		public String getDefaultTaskId() {
			return "$" + VCommanderRunWorkflowAction.ENV_VARIABLE_WORKFLOW_TASK_ID;
		}
		
		
		////////////////////
		// form validations
	
		/**
		 * Called by jelly, to validate task ID field
		 */
		public FormValidation doCheckTaskId(@QueryParameter String taskId) throws IOException, ServletException {
			if(PluginUtils.isNumericOrVariable(taskId)) {
				return FormValidation.ok();
			} else {
				return FormValidation.error(Messages.VCommanderWaitForRunWorkflowAction_errors_taskId());
			}
		}

	}

}
