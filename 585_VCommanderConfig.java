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

package com.embotics.vlm.plugin;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.ws.rs.POST;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.embotics.vlm.rest.v30.client.VCommanderClient;
import com.embotics.vlm.rest.v30.client.model.VCommanderException;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

/**
 * Global configuration for the VCommander plug-in, 
 * Shown on the Jenkins Configure System page.
 * 
 * @author btarczali
 */
@Extension
public final class VCommanderConfig extends GlobalConfiguration {
	
	private String address;
	private String credentialsId;
	private String orgName;

	private VCommanderClient vCommanderClient; //used for Unit testing
	
	
	/**
	 * Constructor, initializes the fields from saved json data
	 */
	public VCommanderConfig() {
		load();
	}

	/**
	 * Get the current VCommanderConfig global configuration.
	 * 
	 * @return the VCommanderConfig global configuration, or {@code null} if Jenkins has been shut down
	 */
	private static VCommanderConfig get() {
		Jenkins jenkins = Jenkins.getInstance();
		if (jenkins != null) {
			VCommanderConfig config = jenkins.getDescriptorByType(VCommanderConfig.class);
			if (config != null) {
				return config;
			}
		}
		return null;
	}
	
	/**
	 * Creates a vComamnder rest client based on the global configuration
	 * 
	 * @return VCommanderClient
	 * @throws VCommanderException 
	 */
	public static VCommanderClient getVCommanderClient() throws VCommanderException {
		VCommanderConfig config = VCommanderConfig.get();
		if(config.vCommanderClient != null) {
			return config.vCommanderClient;
		}

		StandardUsernamePasswordCredentials cred = getCredential(config.getCredentialsId(), config.getAddress());
		if(cred==null) {
			throw new VCommanderException(Messages.VCommanderConfig_connection_failedNoCredential());
		}
		
		return new VCommanderClient(config.getAddress(), cred.getUsername(), Secret.toString(cred.getPassword()), config.getOrgName());
	}

	
	/**
	 * Inject a rest client
	 * Useful for unit testing
	 * 
	 * @param vCommanderClient vCommadner rest client 
	 */
	protected void setvCommanderClient(VCommanderClient vCommanderClient) {
		this.vCommanderClient = vCommanderClient;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean configure(StaplerRequest req, JSONObject json) throws Descriptor.FormException {
		req.bindJSON(this, json);
		save();
		return true;
	}
	
	/**
	 * Look up the credential based on it's ID
	 */
	private static StandardUsernamePasswordCredentials getCredential(String id, String address) {
		List<StandardUsernamePasswordCredentials> creds = CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, (Item)null, ACL.SYSTEM, URIRequirementBuilder.fromUri(address).build());
		return CredentialsMatchers.firstOrNull(creds, CredentialsMatchers.withId(id));
	}
	
	/**
	 * Look up the credential without domain and type filtering
	 */
	private static StandardCredentials getCredentialWithoutDomainAndTypeFiltering(String id) {
		List<StandardCredentials> creds = CredentialsProvider.lookupCredentials(StandardCredentials.class, (Item)null, ACL.SYSTEM, Collections.<DomainRequirement>emptyList());
		return CredentialsMatchers.firstOrNull(creds, CredentialsMatchers.withId(id));
	}
	
	/**
	 * Called by jelly, to populate credentials drop-down
	 */
	public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId, @QueryParameter String address) {
		StandardListBoxModel result = new StandardListBoxModel();
		
		// Lookup the current credentials without any filtering, so the selected item is visible - later validation will prompt if is valid or not
		StandardCredentials currentCred = getCredentialWithoutDomainAndTypeFiltering(credentialsId);

		// We protect against fully populating the drop-down list for users that have no ability to actually make a selection.
		// This is also useful in preventing unwanted requests being made to an external credentials store.
		if (item == null) {
			// To prevent build warning: Possible null pointer dereference
			Jenkins jenkinsInstance = Jenkins.getInstance();
        	if(jenkinsInstance!=null && !jenkinsInstance.hasPermission(Jenkins.ADMINISTER)) {
        		return result.with(currentCred);
        	}
		} else {
			if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
				return result.with(currentCred);
			}
		}

		// Lookup the valid credentials
		List<StandardCredentials> credentials = new ArrayList<>();
		credentials.addAll(CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, item, ACL.SYSTEM, URIRequirementBuilder.fromUri(address).build()));
		
		// Add the current selection; avoid duplicate
		if(currentCred!=null && !credentials.contains(currentCred)) {
			credentials.add(currentCred);
		}
		
		return result
				.withEmptySelection()
				.withAll(credentials);
	}

	
	////////////////////
	// form validations

	/**
	 * Called by jelly, to validate address field
	 */
	public FormValidation doCheckAddress(@QueryParameter String value) throws IOException, ServletException {
		if (value.length() == 0)
			return FormValidation.error(Messages.VCommanderConfig_errors_missingAddress());

		return FormValidation.ok();
	}

	/**
	 * Called by jelly, to validate credential field
	 */
	public FormValidation doCheckCredentialsId(@AncestorInPath Item item, @QueryParameter String address, @QueryParameter String credentialsId) {
		// Return no-op validation results for users that do not have permission to list
		// credentials
		if (item == null) {
        	// To prevent build warning: Possible null pointer dereference
        	Jenkins jenkinsInstance = Jenkins.getInstance();
        	if(jenkinsInstance!=null && !jenkinsInstance.hasPermission(Jenkins.ADMINISTER)) {
				return FormValidation.ok();
        	}
		} else {
			if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
				return FormValidation.ok();
			}
		}

		// When no credentials, do not fail
		if (StringUtils.isBlank(credentialsId)) {
			return FormValidation.ok();
		}
		
		if(getCredential(credentialsId, address) == null) {
			// there is a problem, find out the details
			return findCredentialProblem(credentialsId);
		}

		return FormValidation.ok();
	}
	
	/**
	 * Called by jelly, to validate credentials
	 */
	@POST
	public FormValidation doTestConnection(	@QueryParameter("address") final String address, 
											@QueryParameter("credentialsId") final String credentialsId,
											@QueryParameter("orgName") final String orgName)
											throws IOException, ServletException {
		Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
		// Fail when no credentials
		if (StringUtils.isBlank(credentialsId)) {
			return FormValidation.error(Messages.VCommanderConfig_errors_missingCredential());
		}
		
		StandardUsernamePasswordCredentials cred = getCredential(credentialsId, address);

		// Fail when the credential doesn't exists, wrong domain or wrong type
		if (cred == null) {
			return findCredentialProblem(credentialsId);
		}

		// Test the credentials against vCommander
		try {
			VCommanderClient client = new VCommanderClient(address, cred.getUsername(), Secret.toString(cred.getPassword()), orgName);
			client.getSecurityToken();
			client.close();
			
			return FormValidation.ok(Messages.VCommanderConfig_connection_success());
		} catch (Exception e) {
			String message = e.getMessage();
			if(e.getCause() !=null && e.getCause() instanceof ConnectException) {
				message = Messages.VCommanderConfig_connection_failedConnection();
			}
			return FormValidation.error(Messages.VCommanderConfig_connection_failed(message));
		}
	}
	
	/**
	 * The credential can be invalid due to different reasons
	 * Call this method to check for the problem
	 */
	private FormValidation findCredentialProblem(String id) {
		// check if credential exists without domain restrictions
		if(getCredential(id, null) != null) {
			return FormValidation.error(Messages.VCommanderConfig_errors_invalidCredentialDomain());
		}
		
		// check if credential exists without type check
		if(getCredentialWithoutDomainAndTypeFiltering(id) != null) {
			return FormValidation.error(Messages.VCommanderConfig_errors_invalidCredentialType());
		}
		
		//credential was deleted
		return FormValidation.error(Messages.VCommanderConfig_errors_deletedCredential());
	}


	//////////////////////
	// getters & setters

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public String getCredentialsId() {
		return credentialsId;
	}

	public void setCredentialsId(String credentialsId) {
		this.credentialsId = credentialsId;
	}

	public String getOrgName() {
		return orgName;
	}

	public void setOrgName(String orgName) {
		this.orgName = orgName;
	}


}
