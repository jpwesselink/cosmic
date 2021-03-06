package org.apache.cloudstack.api.command.admin.config;

import com.cloud.hypervisor.HypervisorCapabilities;
import com.cloud.user.Account;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.HypervisorCapabilitiesResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@APICommand(name = "updateHypervisorCapabilities",
        description = "Updates a hypervisor capabilities.",
        responseObject = HypervisorCapabilitiesResponse.class,
        since = "3.0.0",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false)
public class UpdateHypervisorCapabilitiesCmd extends BaseCmd {
    public static final Logger s_logger = LoggerFactory.getLogger(UpdateHypervisorCapabilitiesCmd.class.getName());
    private static final String s_name = "updatehypervisorcapabilitiesresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = HypervisorCapabilitiesResponse.class, description = "ID of the hypervisor capability")
    private Long id;

    @Parameter(name = ApiConstants.SECURITY_GROUP_EANBLED, type = CommandType.BOOLEAN, description = "set true to enable security group for this hypervisor.")
    private Boolean securityGroupEnabled;

    @Parameter(name = ApiConstants.MAX_GUESTS_LIMIT, type = CommandType.LONG, description = "the max number of Guest VMs per host for this hypervisor.")
    private Long maxGuestsLimit;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() {
        final HypervisorCapabilities result = _mgr.updateHypervisorCapabilities(getId(), getMaxGuestsLimit(), getSecurityGroupEnabled());
        if (result != null) {
            final HypervisorCapabilitiesResponse response = _responseGenerator.createHypervisorCapabilitiesResponse(result);
            response.setResponseName(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update hypervisor capabilities");
        }
    }

    public Long getId() {
        return id;
    }

    public Long getMaxGuestsLimit() {
        return maxGuestsLimit;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    public Boolean getSecurityGroupEnabled() {
        return securityGroupEnabled;
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }
}
