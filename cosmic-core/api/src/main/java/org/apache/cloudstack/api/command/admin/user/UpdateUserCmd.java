package org.apache.cloudstack.api.command.admin.user;

import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.UserAccount;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.UserResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.region.RegionService;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@APICommand(name = "updateUser", description = "Updates a user account", responseObject = UserResponse.class,
        requestHasSensitiveInfo = true, responseHasSensitiveInfo = true)
public class UpdateUserCmd extends BaseCmd {
    public static final Logger s_logger = LoggerFactory.getLogger(UpdateUserCmd.class.getName());

    private static final String s_name = "updateuserresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Inject
    RegionService _regionService;
    @Parameter(name = ApiConstants.USER_API_KEY, type = CommandType.STRING, description = "The API key for the user. Must be specified with userSecretKey")
    private String apiKey;
    @Parameter(name = ApiConstants.EMAIL, type = CommandType.STRING, description = "email")
    private String email;
    @Parameter(name = ApiConstants.FIRSTNAME, type = CommandType.STRING, description = "first name")
    private String firstname;
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = UserResponse.class, required = true, description = "User uuid")
    private Long id;
    @Parameter(name = ApiConstants.LASTNAME, type = CommandType.STRING, description = "last name")
    private String lastname;
    @Parameter(name = ApiConstants.PASSWORD,
            type = CommandType.STRING,
            description = "Clear text password (default hashed to SHA256SALT). If you wish to use any other hasing algorithm, you would need to write a custom authentication " +
                    "adapter")
    private String password;
    @Parameter(name = ApiConstants.SECRET_KEY, type = CommandType.STRING, description = "The secret key for the user. Must be specified with userSecretKey")
    private String secretKey;
    @Parameter(name = ApiConstants.TIMEZONE,
            type = CommandType.STRING,
            description = "Specifies a timezone for this command. For more information on the timezone parameter, see Time Zone Format.")
    private String timezone;
    @Parameter(name = ApiConstants.USERNAME, type = CommandType.STRING, description = "Unique username")
    private String username;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public String getApiKey() {
        return apiKey;
    }

    public String getEmail() {
        return email;
    }

    public String getFirstname() {
        return firstname;
    }

    public String getLastname() {
        return lastname;
    }

    public String getPassword() {
        return password;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getUsername() {
        return username;
    }

    @Override
    public void execute() {
        CallContext.current().setEventDetails("UserId: " + getId());
        final UserAccount user = _regionService.updateUser(this);

        if (user != null) {
            final UserResponse response = _responseGenerator.createUserResponse(user);
            response.setResponseName(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update user");
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
        final User user = _entityMgr.findById(User.class, getId());
        if (user != null) {
            return user.getAccountId();
        }

        return Account.ACCOUNT_ID_SYSTEM; // no account info given, parent this command to SYSTEM so ERROR events are tracked
    }

    public Long getId() {
        return id;
    }
}
