package com.embotics.vlm.plugin.actions;

import java.io.IOException;

import javax.servlet.ServletException;

import org.kohsuke.stapler.QueryParameter;

import com.embotics.vlm.plugin.Messages;
import com.embotics.vlm.plugin.VCommanderAction;

import hudson.model.Descriptor;
import hudson.util.FormValidation;

/**
 * A base class to be extended by the vCommadner actions.
 * The common configurations and logic are implemented here
 *
 * @author btarczali
 */
public abstract class AbstractVCommanderAction implements VCommanderAction {

	public static final long DEFAULT_TIMEOUT_VALUE = 10; // minutes
	public static final long MINIMUM_TIMEOUT_VALUE = 1; // minutes
	public static final long DEFAULT_POLLING_VALUE = 15; // seconds
	public static final long MINIMUM_POLLING_VALUE = 10; // seconds
	
	private final boolean sync;
	private final long timeout; // in minutes
	private final long polling; // in seconds
	
	
	public AbstractVCommanderAction(boolean sync, Long timeout, Long polling) {
		this.sync = sync;
		this.timeout = getDefault(timeout, MINIMUM_TIMEOUT_VALUE, DEFAULT_TIMEOUT_VALUE);
		this.polling = getDefault(polling, MINIMUM_POLLING_VALUE, DEFAULT_POLLING_VALUE);
	}
	
	public static long getDefault(Long value, long minValue, long defaultValue) {
		return (value == null || value < minValue) ? defaultValue : value;
	}
	
	public Boolean getSync() {
		return sync;
	}

	public Long getTimeout() {
		return timeout;
	}

	public Long getPolling() {
		return polling;
	}
	
	public static abstract class AbstractVCommanderActionDescriptor extends Descriptor<VCommanderAction> {
		
		////////////////////
		// form validations

		/**
		 * Called by jelly, to validate timeout field
		 */
		public FormValidation doCheckTimeout(@QueryParameter Long value) throws IOException, ServletException {
			if (value == null || value < MINIMUM_TIMEOUT_VALUE) {
				return FormValidation.error(Messages.VCommanderBuilder_errors_missingTimeout());
			}

			return FormValidation.ok();
		}

		/**
		 * Called by jelly, to validate polling field
		 */
		public FormValidation doCheckPolling(@QueryParameter Long polling, @QueryParameter Long timeout) throws IOException, ServletException {
			if (polling == null ||polling < MINIMUM_POLLING_VALUE) {
				return FormValidation.error(Messages.VCommanderBuilder_errors_missingPolling());
			}
			
			// timeout is in minutes, polling is in seconds
			if (timeout!=null && timeout>=MINIMUM_TIMEOUT_VALUE && timeout*60 < polling) {
				return FormValidation.error(Messages.VCommanderBuilder_errors_invalidPolling());
			}

			return FormValidation.ok();
		}
	}
}
