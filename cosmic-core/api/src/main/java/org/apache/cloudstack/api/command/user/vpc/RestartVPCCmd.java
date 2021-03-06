package org.apache.cloudstack.api.command.user.vpc;

import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.vpc.Vpc;
import com.cloud.user.Account;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.api.response.VpcResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@APICommand(name = "restartVPC", description = "Restarts a VPC", responseObject = VpcResponse.class, entityType = {Vpc.class},
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class RestartVPCCmd extends BaseAsyncCmd {
    public static final Logger s_logger = LoggerFactory.getLogger(RestartVPCCmd.class.getName());
    private static final String s_name = "restartvpcresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @ACL(accessType = AccessType.OperateEntry)
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = VpcResponse.class, required = true, description = "the id of the VPC")
    private Long id;

    @Parameter(name = ApiConstants.CLEANUP, type = CommandType.BOOLEAN, required = false, description = "If cleanup old network elements")
    private Boolean cleanup;

    @Parameter(name = ApiConstants.MAKEREDUNDANTE, type = CommandType.BOOLEAN, required = false, description = "Turn a single VPC into a redundant one.")
    private Boolean makeredundant;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() {
        try {
            final boolean result = _vpcService.restartVpc(getId(), getCleanup(), getMakeredundant());
            if (result) {
                final SuccessResponse response = new SuccessResponse(getCommandName());
                setResponseObject(response);
            } else {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to restart VPC");
            }
        } catch (final ResourceUnavailableException ex) {
            s_logger.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR, ex.getMessage());
        } catch (final ConcurrentOperationException ex) {
            s_logger.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        } catch (final InsufficientCapacityException ex) {
            s_logger.info(ex.toString());
            throw new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, ex.getMessage());
        }
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////
    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        final Vpc vpc = _entityMgr.findById(Vpc.class, getId());
        if (vpc != null) {
            return vpc.getAccountId();
        }

        return Account.ACCOUNT_ID_SYSTEM; // no account info given, parent this command to SYSTEM so ERROR events are tracked
    }

    public Long getId() {
        return id;
    }

    public Boolean getCleanup() {
        if (cleanup != null) {
            return cleanup;
        }
        return true;
    }

    public Boolean getMakeredundant() {
        if (makeredundant != null) {
            return makeredundant;
        }
        return true;
    }

    @Override
    public String getEventType() {
        return EventTypes.EVENT_VPC_RESTART;
    }

    @Override
    public String getEventDescription() {
        return "restarting VPC id=" + getId();
    }

    @Override
    public String getSyncObjType() {
        return BaseAsyncCmd.vpcSyncObject;
    }

    @Override
    public Long getSyncObjId() {
        return getId();
    }
}
