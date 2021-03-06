package org.apache.cloudstack.api.command.user.volume;

import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.projects.Project;
import com.cloud.storage.Volume;
import com.cloud.user.Account;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandJobType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.context.CallContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@APICommand(name = "resizeVolume", description = "Resizes a volume", responseObject = VolumeResponse.class, responseView = ResponseView.Restricted, entityType = {Volume.class},
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ResizeVolumeCmd extends BaseAsyncCmd {
    public static final Logger s_logger = LoggerFactory.getLogger(ResizeVolumeCmd.class.getName());

    private static final String s_name = "resizevolumeresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @ACL(accessType = AccessType.OperateEntry)
    @Parameter(name = ApiConstants.ID, entityType = VolumeResponse.class, required = true, type = CommandType.UUID, description = "the ID of the disk volume")
    private Long id;

    @Parameter(name = ApiConstants.MIN_IOPS, type = CommandType.LONG, required = false, description = "New minimum number of IOPS")
    private Long minIops;

    @Parameter(name = ApiConstants.MAX_IOPS, type = CommandType.LONG, required = false, description = "New maximum number of IOPS")
    private Long maxIops;

    @Parameter(name = ApiConstants.SIZE, type = CommandType.LONG, required = false, description = "New volume size in GB")
    private Long size;

    @Parameter(name = ApiConstants.SHRINK_OK, type = CommandType.BOOLEAN, required = false, description = "Verify OK to Shrink")
    private boolean shrinkOk;

    @Parameter(name = ApiConstants.DISK_OFFERING_ID,
            entityType = DiskOfferingResponse.class,
            type = CommandType.UUID,
            required = false,
            description = "new disk offering id")
    private Long newDiskOfferingId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public static String getResultObjectName() {
        return "volume";
    }

    public Long getId() {
        return getEntityId();
    }

    //TODO use the method getId() instead of this one.
    public Long getEntityId() {
        return id;
    }

    public Long getMinIops() {
        return minIops;
    }

    public Long getMaxIops() {
        return maxIops;
    }

    public boolean getShrinkOk() {
        return shrinkOk;
    }

    public Long getNewDiskOfferingId() {
        return newDiskOfferingId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getEventType() {
        return EventTypes.EVENT_VOLUME_RESIZE;
    }

    @Override
    public String getEventDescription() {
        return "Volume Id: " + getEntityId() + " to size " + getSize() + "G";
    }

    public Long getSize() {
        return size;
    }

    @Override
    public ApiCommandJobType getInstanceType() {
        return ApiCommandJobType.Volume;
    }

    @Override
    public void execute() throws ResourceAllocationException {
        Volume volume = null;
        try {
            CallContext.current().setEventDetails("Volume Id: " + getEntityId() + " to size " + getSize() + "G");
            volume = _volumeService.resizeVolume(this);
        } catch (final InvalidParameterValueException ex) {
            s_logger.info(ex.getMessage());
            throw new ServerApiException(ApiErrorCode.UNSUPPORTED_ACTION_ERROR, ex.getMessage());
        }

        if (volume != null) {
            final VolumeResponse response = _responseGenerator.createVolumeResponse(ResponseView.Restricted, volume);
            //FIXME - have to be moved to ApiResponseHelper
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to resize volume");
        }
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {

        final Volume volume = _entityMgr.findById(Volume.class, getEntityId());
        if (volume == null) {
            throw new InvalidParameterValueException("Unable to find volume by id=" + id);
        }

        final Account account = _accountService.getAccount(volume.getAccountId());
        //Can resize volumes for enabled projects/accounts only
        if (account.getType() == Account.ACCOUNT_TYPE_PROJECT) {
            final Project project = _projectService.findByProjectAccountId(volume.getAccountId());
            if (project.getState() != Project.State.Active) {
                throw new PermissionDeniedException("Can't add resources to  project id=" + project.getId() + " in state=" + project.getState() +
                        " as it's no longer active");
            }
        } else if (account.getState() == Account.State.disabled) {
            throw new PermissionDeniedException("The owner of volume " + id + "  is disabled: " + account);
        }

        return volume.getAccountId();
    }
}
