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

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.embotics.vlm.plugin.Messages;
import com.embotics.vlm.plugin.VCommanderAction;
import com.embotics.vlm.plugin.VCommanderConfig;
import com.embotics.vlm.rest.v30.client.VCommanderClient;
import com.embotics.vlm.rest.v30.client.model.VCommanderException;
import com.embotics.vlm.rest.v30.client.model.WorkflowTargetType;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;

/**
 * A vCommander Action, which triggers a command workflow
 * 
 * @author btarczali
 */
public class VCommanderRunWorkflowAction extends AbstractVCommanderAction {
	static final String ENV_VARIABLE_WORKFLOW_TASK_ID = "VCOMMANDER_WORKFLOW_TASK_ID";
	
	private final String targetType;
	private final String targetName;
	private final String workflowName;
	

	@DataBoundConstructor
	public VCommanderRunWorkflowAction(String targetType, String targetName, String workflowName, Boolean sync, Long timeout, Long polling) {
		super(sync, timeout, polling);
		this.targetType = targetType;
		this.targetName = targetName;
		this.workflowName = workflowName;
	}

	public String getTargetType() {
		return targetType;
	}

	public String getTargetName() {
		return targetName;
	}
	
	public String getWorkflowName() {
		return workflowName;
	}

	@Override
	public void perform(Run<?, ?> run, TaskListener listener) throws InterruptedException, IOException {
		// check if user configured a workflow
		if (StringUtils.isBlank(workflowName)) {
			throw new VCommanderException("There is no configuration for the Commander workflow build step.");
		}

		VCommanderClient client = VCommanderConfig.getVCommanderClient();
		listener.getLogger().println(client.getClientInfo());

		try {
			Long workflowDefinitionId;
			try {
				listener.getLogger().println("Looking up command workflow with name: '" + workflowName + "'");
				workflowDefinitionId = client.getWorkflowDefinition(workflowName);
			} catch (JSONException e) {
				throw new VCommanderException(e, "Error while retrieving workflow from Commander.");
			}
			
			String taskId;
			try {
				// run workflow
				String resolvedTargetName = Util.replaceMacro(targetName, run.getEnvironment(listener));
				String resolvedTargetType = Util.replaceMacro(targetType, run.getEnvironment(listener));
				
				listener.getLogger().println("Running command workflow: '" + workflowName + "' for target: '" + resolvedTargetName + "' with type: " + resolvedTargetType);
				taskId = client.runCommandWorkflow(workflowDefinitionId, resolvedTargetType, resolvedTargetName);
				listener.getLogger().println("Command workflow submitted to Commander. Task ID: " + taskId);
				
				//create environment variables for the results
				PluginUtils.addEnvVariable(run, listener, ENV_VARIABLE_WORKFLOW_TASK_ID, taskId);
			} catch (JSONException e) {
				throw new VCommanderException(e, "Error while running command workflow in Commander.");
			}

			if(getSync()) {
				waitForWorkflowToBeCompleted(client, taskId, getTimeout(), getPolling(), run, listener);
			}
		} finally {
			client.close();
		}
	}
	
	static void waitForWorkflowToBeCompleted(VCommanderClient client, String taskId, long timeout, long polling, Run<?, ?> run, TaskListener listener) throws VCommanderException, InterruptedException {
		try {
			// monitor the workflow until is completed or failed
			listener.getLogger().println("Waiting " + timeout + " minutes for workflow completion. Checking every " + polling + " seconds ...");
			client.waitForWorkflowToBeCompleted(taskId, timeout, polling, listener.getLogger());
			listener.getLogger().println("Workflow successfully completed.");
		} catch (JSONException e) {
			throw new VCommanderException(e, "Error while waiting for workflow completion.");
		}
	}


	@Extension(ordinal=156)
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    
    public Descriptor<VCommanderAction> getDescriptor() {
        return DESCRIPTOR;
    }
	
	public static final class DescriptorImpl extends AbstractVCommanderActionDescriptor {

		@Override
		public String getDisplayName() {
			return Messages.VCommanderRunWorkflowAction_displayName();
		}

		/**
		 * Called by jelly, to populate the target types combo box
		 */
		public ComboBoxModel doFillTargetTypeItems() {
			ComboBoxModel items = new ComboBoxModel();
		    for (WorkflowTargetType type : WorkflowTargetType.values()) {
		        items.add(type.name());
		    }
		    return items;
		}
    	
		
		////////////////////
		// form validations
	
		/**
		 * Called by jelly, to validate targetType field
		 */
		public FormValidation doCheckTargetType(@QueryParameter String targetType) throws IOException, ServletException {
			// if no content, do not return error;
			// we do not want to show the initial form with error
			if (StringUtils.isBlank(targetType)) {
				return FormValidation.ok();
			}

			if(!PluginUtils.hasVariable(targetType) && !isTargetTypeValid(targetType)) {
				return FormValidation.error(Messages.VCommanderRunWorkflowAction_errors_targetTypeInvalid(targetType));
			}
			
			return FormValidation.ok();
		}
		
		/**
		 * Called by jelly, to validate targetName field
		 */
		public FormValidation doCheckTargetName(@QueryParameter String targetType, @QueryParameter String targetName) throws IOException, ServletException {
			// if no content, do not return error;
			// we do not want to show the initial form with error
			if (StringUtils.isBlank(targetName)) {
				return FormValidation.ok();
			}

			if(WorkflowTargetType.NO_INVENTORY_TARGET.name().equals(targetType) && StringUtils.isNotBlank(targetName)) {
				return FormValidation.error(Messages.VCommanderRunWorkflowAction_errors_targetNameInvalid(targetType));
			}
			
			return FormValidation.ok();
		}
		
		/**
		 * Called by jelly, to validate workflowName field
		 */
		public FormValidation doCheckWorkflowName(@QueryParameter String targetType, @QueryParameter String workflowName) throws IOException, ServletException {
			// if no content, do not return error;
			// we do not want to show the initial form with error
			if (StringUtils.isBlank(workflowName)) {
				return FormValidation.ok();
			}

			VCommanderClient client = VCommanderConfig.getVCommanderClient();
			try {
				
				Long workflowDefinitionId;
				try {
					workflowDefinitionId = client.getWorkflowDefinition(workflowName);
				} catch (Exception e) {
					if(e.getCause() !=null && e.getCause() instanceof ConnectException) {
						return FormValidation.error(Messages.VCommanderConfig_connection_failedConnection());
					}
					return FormValidation.error(e.getMessage());
				}
				
				try {
					String workflowTargetType = client.getWorkflowDefinitionTargetType(workflowDefinitionId);
					
					// for workflows without target
					if(WorkflowTargetType.NO_INVENTORY_TARGET.name().equals(workflowTargetType)) {
						// target type must be the no target value
						if(!workflowTargetType.equals(targetType)) {
							return FormValidation.error(Messages.VCommanderRunWorkflowAction_errors_targetTypeDoNotMatch(targetType, workflowTargetType));
						}
						
					// for workflows with target
					} else {
						// when target type is defined and known (not variable), then it should be compatible with the workflow 
						if(StringUtils.isBlank(targetType) 
								|| !PluginUtils.hasVariable(targetType) && (
									!isTargetTypeValid(targetType) 
									|| WorkflowTargetType.WORKFLOW_TYPE_FOR_ANY_INVENTORY_TYPE.equals(workflowTargetType) && WorkflowTargetType.NO_INVENTORY_TARGET.name().equals(targetType) 
									|| !WorkflowTargetType.WORKFLOW_TYPE_FOR_ANY_INVENTORY_TYPE.equals(workflowTargetType) && !targetType.equals(workflowTargetType)
								)) {
							String displayWorkflowTargetType = WorkflowTargetType.WORKFLOW_TYPE_FOR_ANY_INVENTORY_TYPE.equals(workflowTargetType) ? Messages.VCommanderRunWorkflowAction_targetType_ALL() : workflowTargetType;
							return FormValidation.error(Messages.VCommanderRunWorkflowAction_errors_targetTypeDoNotMatch(targetType, displayWorkflowTargetType));
						}	
					}
				} catch (Exception e) {
					return FormValidation.error(e.getMessage());
				}

			} finally {
				client.close();
			}
			
			return FormValidation.ok();
		}
		
		private boolean isTargetTypeValid(String resolvedTargetType) {
			try {
				WorkflowTargetType.valueOf(resolvedTargetType);
				return true;
			} catch(IllegalArgumentException e) {
				return false;
			}
		}

	}

}
