package com.cloud.alert;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.storage.secondary.SecStorageVmAlertEventArgs;
import com.cloud.storage.secondary.SecondaryStorageVmManager;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.events.SubscriptionMgr;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.SecondaryStorageVmVO;
import com.cloud.vm.dao.SecondaryStorageVmDao;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SecondaryStorageVmAlertAdapter extends AdapterBase implements AlertAdapter {

    private static final Logger s_logger = LoggerFactory.getLogger(SecondaryStorageVmAlertAdapter.class);

    @Inject
    private AlertManager _alertMgr;
    @Inject
    private DataCenterDao _dcDao;
    @Inject
    private SecondaryStorageVmDao _ssvmDao;

    public void onSSVMAlert(final Object sender, final SecStorageVmAlertEventArgs args) {
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("received secondary storage vm alert");
        }

        final DataCenterVO dc = _dcDao.findById(args.getZoneId());
        SecondaryStorageVmVO secStorageVm = args.getSecStorageVm();
        if (secStorageVm == null && args.getSecStorageVmId() != 0) {
            secStorageVm = _ssvmDao.findById(args.getSecStorageVmId());
        }

        if (secStorageVm == null && args.getType() != SecStorageVmAlertEventArgs.SSVM_CREATE_FAILURE) {
            throw new CloudRuntimeException("Invalid alert arguments, secStorageVm must be set");
        }

        switch (args.getType()) {
            case SecStorageVmAlertEventArgs.SSVM_CREATED:
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("New secondary storage vm created, zone: " + dc.getName() + ", secStorageVm: " + secStorageVm.getHostName() + ", public IP: " +
                            secStorageVm.getPublicIpAddress() + ", private IP: " + secStorageVm.getPrivateIpAddress());
                }
                break;

            case SecStorageVmAlertEventArgs.SSVM_UP:
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Secondary Storage Vm is up, zone: " + dc.getName() + ", secStorageVm: " + secStorageVm.getHostName() + ", public IP: " +
                            secStorageVm.getPublicIpAddress() + ", private IP: " + secStorageVm.getPrivateIpAddress());
                }

                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SSVM, args.getZoneId(), secStorageVm.getPodIdToDeployIn(), "Secondary Storage Vm up in zone: " +
                        dc.getName() + ", secStorageVm: " + secStorageVm.getHostName() + ", public IP: " + secStorageVm.getPublicIpAddress() + ", private IP: " +
                        (secStorageVm.getPrivateIpAddress() == null ? "N/A" : secStorageVm.getPrivateIpAddress()), "Secondary Storage Vm up (zone " + dc.getName() + ")");
                break;

            case SecStorageVmAlertEventArgs.SSVM_DOWN:
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Secondary Storage Vm is down, zone: " + dc.getName() + ", secStorageVm: " + secStorageVm.getHostName() + ", public IP: " +
                            secStorageVm.getPublicIpAddress() + ", private IP: " + (secStorageVm.getPrivateIpAddress() == null ? "N/A" : secStorageVm.getPrivateIpAddress()));
                }

                _alertMgr.sendAlert(
                        AlertManager.AlertType.ALERT_TYPE_SSVM,
                        args.getZoneId(),
                        secStorageVm.getPodIdToDeployIn(),
                        "Secondary Storage Vm down in zone: " + dc.getName() + ", secStorageVm: " + secStorageVm.getHostName() + ", public IP: " +
                                secStorageVm.getPublicIpAddress() + ", private IP: " + (secStorageVm.getPrivateIpAddress() == null ? "N/A" : secStorageVm.getPrivateIpAddress()),
                        "Secondary Storage Vm down (zone " + dc.getName() + ")");
                break;

            case SecStorageVmAlertEventArgs.SSVM_REBOOTED:
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Secondary Storage Vm is rebooted, zone: " + dc.getName() + ", secStorageVm: " + secStorageVm.getHostName() + ", public IP: " +
                            secStorageVm.getPublicIpAddress() + ", private IP: " + (secStorageVm.getPrivateIpAddress() == null ? "N/A" : secStorageVm.getPrivateIpAddress()));
                }

                _alertMgr.sendAlert(
                        AlertManager.AlertType.ALERT_TYPE_SSVM,
                        args.getZoneId(),
                        secStorageVm.getPodIdToDeployIn(),
                        "Secondary Storage Vm rebooted in zone: " + dc.getName() + ", secStorageVm: " + secStorageVm.getHostName() + ", public IP: " +
                                secStorageVm.getPublicIpAddress() + ", private IP: " + (secStorageVm.getPrivateIpAddress() == null ? "N/A" : secStorageVm.getPrivateIpAddress()),
                        "Secondary Storage Vm rebooted (zone " + dc.getName() + ")");
                break;

            case SecStorageVmAlertEventArgs.SSVM_CREATE_FAILURE:
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Secondary Storage Vm creation failure, zone: " + dc.getName());
                }

                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SSVM, args.getZoneId(), null,
                        "Secondary Storage Vm creation failure. zone: " + dc.getName() + ", error details: " + args.getMessage(),
                        "Secondary Storage Vm creation failure (zone " + dc.getName() + ")");
                break;

            case SecStorageVmAlertEventArgs.SSVM_START_FAILURE:
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Secondary Storage Vm startup failure, zone: " + dc.getName() + ", secStorageVm: " + secStorageVm.getHostName() + ", public IP: " +
                            secStorageVm.getPublicIpAddress() + ", private IP: " + (secStorageVm.getPrivateIpAddress() == null ? "N/A" : secStorageVm.getPrivateIpAddress()));
                }

                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SSVM, args.getZoneId(), secStorageVm.getPodIdToDeployIn(),
                        "Secondary Storage Vm startup failure. zone: " +
                                dc.getName() + ", secStorageVm: " + secStorageVm.getHostName() + ", public IP: " + secStorageVm.getPublicIpAddress() + ", private IP: " +
                                (secStorageVm.getPrivateIpAddress() == null ? "N/A" : secStorageVm.getPrivateIpAddress()) + ", error details: " + args.getMessage(),
                        "Secondary Storage Vm startup failure (zone " + dc.getName() + ")");
                break;

            case SecStorageVmAlertEventArgs.SSVM_FIREWALL_ALERT:
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Secondary Storage Vm firewall alert, zone: " + dc.getName() + ", secStorageVm: " + secStorageVm.getHostName() + ", public IP: " +
                            secStorageVm.getPublicIpAddress() + ", private IP: " + (secStorageVm.getPrivateIpAddress() == null ? "N/A" : secStorageVm.getPrivateIpAddress()));
                }

                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_SSVM, args.getZoneId(), secStorageVm.getPodIdToDeployIn(),
                        "Failed to open secondary storage vm firewall port. zone: " + dc.getName() + ", secStorageVm: " + secStorageVm.getHostName() + ", public IP: " +
                                secStorageVm.getPublicIpAddress() + ", private IP: " + (secStorageVm.getPrivateIpAddress() == null ? "N/A" : secStorageVm.getPrivateIpAddress()),
                        "Secondary Storage Vm alert (zone " + dc.getName() + ")");
                break;

            case SecStorageVmAlertEventArgs.SSVM_STORAGE_ALERT:
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Secondary Storage Vm storage alert, zone: " + dc.getName() + ", secStorageVm: " + secStorageVm.getHostName() + ", public IP: " +
                            secStorageVm.getPublicIpAddress() + ", private IP: " + secStorageVm.getPrivateIpAddress() + ", message: " + args.getMessage());
                }

                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_STORAGE_MISC, args.getZoneId(), secStorageVm.getPodIdToDeployIn(),
                        "Secondary Storage Vm storage issue. zone: " + dc.getName() + ", message: " + args.getMessage(), "Secondary Storage Vm alert (zone " + dc.getName() +
                                ")");
                break;
        }
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {

        if (s_logger.isInfoEnabled()) {
            s_logger.info("Start configuring secondary storage vm alert manager : " + name);
        }

        try {
            SubscriptionMgr.getInstance().subscribe(SecondaryStorageVmManager.ALERT_SUBJECT, this, "onSSVMAlert");
        } catch (final SecurityException e) {
            throw new ConfigurationException("Unable to register secondary storage vm event subscription, exception: " + e);
        } catch (final NoSuchMethodException e) {
            throw new ConfigurationException("Unable to register secondary storage vm event subscription, exception: " + e);
        }

        return true;
    }
}
