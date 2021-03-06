package org.apache.cloudstack.api.command.admin.config;

import com.cloud.user.Account;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.api.response.ConfigurationResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.config.Configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@APICommand(name = "updateConfiguration", description = "Updates a configuration.", responseObject = ConfigurationResponse.class,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class UpdateCfgCmd extends BaseCmd {
    public static final Logger s_logger = LoggerFactory.getLogger(UpdateCfgCmd.class.getName());
    private static final String s_name = "updateconfigurationresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true, description = "the name of the configuration")
    private String cfgName;

    @Parameter(name = ApiConstants.VALUE, type = CommandType.STRING, description = "the value of the configuration", length = 4095)
    private String value;

    @Parameter(name = ApiConstants.ZONE_ID,
            type = CommandType.UUID,
            entityType = ZoneResponse.class,
            description = "the ID of the Zone to update the parameter value for corresponding zone")
    private Long zoneId;

    @Parameter(name = ApiConstants.CLUSTER_ID,
            type = CommandType.UUID,
            entityType = ClusterResponse.class,
            description = "the ID of the Cluster to update the parameter value for corresponding cluster")
    private Long clusterId;

    @Parameter(name = ApiConstants.STORAGE_ID,
            type = CommandType.UUID,
            entityType = StoragePoolResponse.class,
            description = "the ID of the Storage pool to update the parameter value for corresponding storage pool")
    private Long storagePoolId;

    @Parameter(name = ApiConstants.ACCOUNT_ID,
            type = CommandType.UUID,
            entityType = AccountResponse.class,
            description = "the ID of the Account to update the parameter value for corresponding account")
    private Long accountId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public String getCfgName() {
        return cfgName;
    }

    public String getValue() {
        return value;
    }

    @Override
    public void execute() {
        final Configuration cfg = _configService.updateConfiguration(this);
        if (cfg != null) {
            final ConfigurationResponse response = _responseGenerator.createConfigurationResponse(cfg);
            response.setResponseName(getCommandName());
            if (getZoneId() != null) {
                response.setScope("zone");
            }
            if (getClusterId() != null) {
                response.setScope("cluster");
            }
            if (getStoragepoolId() != null) {
                response.setScope("storagepool");
            }
            if (getAccountId() != null) {
                response.setScope("account");
            }
            response.setValue(value);
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update config");
        }
    }

    public Long getZoneId() {
        return zoneId;
    }

    public Long getClusterId() {
        return clusterId;
    }

    public Long getStoragepoolId() {
        return storagePoolId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    public Long getAccountId() {
        return accountId;
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
