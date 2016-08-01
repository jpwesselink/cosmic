package com.cloud.storage.secondary;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.SecStorageFirewallCfgCommand;
import com.cloud.agent.api.SecStorageSetupAnswer;
import com.cloud.agent.api.SecStorageSetupCommand;
import com.cloud.agent.api.SecStorageVMSetupCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupSecondaryStorageCommand;
import com.cloud.agent.api.check.CheckSshAnswer;
import com.cloud.agent.api.check.CheckSshCommand;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.manager.Commands;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManagerImpl;
import com.cloud.configuration.ZoneConfig;
import com.cloud.consoleproxy.ConsoleProxyManager;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.info.RunningHostInfoAgregator;
import com.cloud.info.RunningHostInfoAgregator.ZoneHostInfo;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.StorageNetworkManager;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceStateAdapter;
import com.cloud.resource.ServerResource;
import com.cloud.resource.UnableDeleteHostException;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage;
import com.cloud.storage.UploadVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.UploadDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.template.TemplateConstants;
import com.cloud.systemvm.SystemVmManagerBase;
import com.cloud.template.TemplateManager;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.QueryBuilder;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.events.SubscriptionMgr;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.SecondaryStorageVm;
import com.cloud.vm.SecondaryStorageVmVO;
import com.cloud.vm.SystemVmLoadScanHandler;
import com.cloud.vm.SystemVmLoadScanner;
import com.cloud.vm.SystemVmLoadScanner.AfterScanAction;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineGuru;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineName;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.SecondaryStorageVmDao;
import com.cloud.vm.dao.UserVmDetailsDao;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.config.ApiServiceConfiguration;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.security.keystore.KeystoreManager;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//
// Possible secondary storage vm state transition cases
//        Creating -> Destroyed
//        Creating -> Stopped --> Starting -> Running
//        HA -> Stopped -> Starting -> Running
//        Migrating -> Running    (if previous state is Running before it enters into Migrating state
//        Migrating -> Stopped    (if previous state is not Running before it enters into Migrating state)
//        Running -> HA            (if agent lost connection)
//        Stopped -> Destroyed
//
//        Creating state indicates of record creating and IP address allocation are ready, it is a transient
//         state which will soon be switching towards Running if everything goes well.
//        Stopped state indicates the readiness of being able to start (has storage and IP resources allocated)
//        Starting state can only be entered from Stopped states
//
// Starting, HA, Migrating, Creating and Running state are all counted as "Open" for available capacity calculation
// because sooner or later, it will be driven into Running state
//
public class SecondaryStorageManagerImpl extends SystemVmManagerBase implements SecondaryStorageVmManager, VirtualMachineGuru, SystemVmLoadScanHandler<Long>, ResourceStateAdapter {
    private static final Logger s_logger = LoggerFactory.getLogger(SecondaryStorageManagerImpl.class);

    private static final int DEFAULT_CAPACITY_SCAN_INTERVAL = 30000; // 30
    // seconds
    private static final int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_SYNC = 180; // 3
    // minutes

    private static final int STARTUP_DELAY = 60000; // 60 seconds
    private final GlobalLock _allocLock = GlobalLock.getInternLock(getAllocLockName());
    @Inject
    protected SecondaryStorageVmDao _secStorageVmDao;
    @Inject
    protected StorageNetworkManager _sNwMgr;
    @Inject
    protected NetworkOrchestrationService _networkMgr;
    @Inject
    protected NetworkModel _networkModel;
    @Inject
    protected SnapshotDao _snapshotDao;
    @Inject
    protected ConfigurationDao _configDao;
    @Inject
    protected VMInstanceDao _vmDao;
    @Inject
    protected CapacityDao _capacityDao;
    @Inject
    protected ResourceManager _resourceMgr;
    @Inject
    protected IPAddressDao _ipAddressDao = null;
    @Inject
    protected RulesManager _rulesMgr;
    @Inject
    UserVmDetailsDao _vmDetailsDao;
    @Inject
    NetworkDao _networkDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    TemplateManager templateMgr;
    @Inject
    UploadDao _uploadDao;
    @Inject
    KeystoreManager _keystoreMgr;
    @Inject
    DataStoreManager _dataStoreMgr;
    @Inject
    ImageStoreDao _imageStoreDao;
    @Inject
    TemplateDataStoreDao _tmplStoreDao;
    @Inject
    VolumeDataStoreDao _volumeStoreDao;
    private int _mgmtPort = 8250;
    @Inject
    private DataCenterDao _dcDao;
    @Inject
    private VMTemplateDao _templateDao;
    @Inject
    private HostDao _hostDao;
    @Inject
    private StoragePoolHostDao _storagePoolHostDao;
    @Inject
    private AgentManager _agentMgr;
    private ServiceOfferingVO _serviceOffering;
    @Inject
    private ServiceOfferingDao _offeringDao;
    @Inject
    private AccountService _accountMgr;
    @Inject
    private VirtualMachineManager _itMgr;
    private int _secStorageVmMtuSize;
    private String _instance;
    private boolean _useSSlCopy;
    private String _allowedInternalSites;
    private SystemVmLoadScanner<Long> _loadScanner;
    private Map<Long, ZoneHostInfo> _zoneHostInfoMap; // map <zone id, info about running host in zone>

    public SecondaryStorageManagerImpl() {
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        if (s_logger.isInfoEnabled()) {
            s_logger.info("Start configuring secondary storage vm manager : " + name);
        }

        final Map<String, String> configs = _configDao.getConfiguration("management-server", params);

        _secStorageVmMtuSize = NumbersUtil.parseInt(configs.get("secstorage.vm.mtu.size"), DEFAULT_SS_VM_MTUSIZE);
        final String useServiceVM = _configDao.getValue("secondary.storage.vm");
        boolean _useServiceVM = false;
        if ("true".equalsIgnoreCase(useServiceVM)) {
            _useServiceVM = true;
        }

        final String sslcopy = _configDao.getValue("secstorage.encrypt.copy");
        if ("true".equalsIgnoreCase(sslcopy)) {
            _useSSlCopy = true;
        }

        //default to HTTP in case of missing domain
        final String ssvmUrlDomain = _configDao.getValue("secstorage.ssl.cert.domain");
        if (_useSSlCopy && (ssvmUrlDomain == null || ssvmUrlDomain.isEmpty())) {
            s_logger.warn("Empty secondary storage url domain, explicitly disabling SSL");
            _useSSlCopy = false;
        }

        _allowedInternalSites = _configDao.getValue("secstorage.allowed.internal.sites");

        String value = configs.get("secstorage.capacityscan.interval");
        final long _capacityScanInterval = NumbersUtil.parseLong(value, DEFAULT_CAPACITY_SCAN_INTERVAL);

        _instance = configs.get("instance.name");
        if (_instance == null) {
            _instance = "DEFAULT";
        }

        final Map<String, String> agentMgrConfigs = _configDao.getConfiguration("AgentManager", params);

        value = agentMgrConfigs.get("port");
        _mgmtPort = NumbersUtil.parseInt(value, 8250);

        final SecondaryStorageListener _listener = new SecondaryStorageListener(this);
        _agentMgr.registerForHostEvents(_listener, true, false, true);

        _itMgr.registerGuru(VirtualMachine.Type.SecondaryStorageVm, this);

        //check if there is a default service offering configured
        final String ssvmSrvcOffIdStr = configs.get(Config.SecondaryStorageServiceOffering.key());
        if (ssvmSrvcOffIdStr != null) {
            _serviceOffering = _offeringDao.findByUuid(ssvmSrvcOffIdStr);
            if (_serviceOffering == null) {
                try {
                    _serviceOffering = _offeringDao.findById(Long.parseLong(ssvmSrvcOffIdStr));
                } catch (final NumberFormatException ex) {
                    s_logger.debug("The system service offering specified by global config is not id, but uuid=" + ssvmSrvcOffIdStr + " for secondary storage vm");
                }
            }
            if (_serviceOffering == null) {
                s_logger.warn("Can't find system service offering specified by global config, uuid=" + ssvmSrvcOffIdStr + " for secondary storage vm");
            }
        }

        if (_serviceOffering == null || !_serviceOffering.getSystemUse()) {
            final int ramSize = NumbersUtil.parseInt(_configDao.getValue("ssvm.ram.size"), DEFAULT_SS_VM_RAMSIZE);
            final int cpuFreq = NumbersUtil.parseInt(_configDao.getValue("ssvm.cpu.mhz"), DEFAULT_SS_VM_CPUMHZ);
            final List<ServiceOfferingVO> offerings = _offeringDao.createSystemServiceOfferings("System Offering For Secondary Storage VM",
                    ServiceOffering.ssvmDefaultOffUniqueName, 1, ramSize, cpuFreq, null, null, false, null,
                    Storage.ProvisioningType.THIN, true, null, true, VirtualMachine.Type.SecondaryStorageVm, true);
            // this can sometimes happen, if DB is manually or programmatically manipulated
            if (offerings == null || offerings.size() < 2) {
                final String msg = "Data integrity problem : System Offering For Secondary Storage VM has been removed?";
                s_logger.error(msg);
                throw new ConfigurationException(msg);
            }
        }

        if (_useServiceVM) {
            _loadScanner = new SystemVmLoadScanner<>(this);
            _loadScanner.initScan(STARTUP_DELAY, _capacityScanInterval);
        }

        String _httpProxy = configs.get(Config.SecStorageProxy.key());
        if (_httpProxy != null) {
            boolean valid = true;
            String errMsg = null;
            try {
                final URI uri = new URI(_httpProxy);
                if (!"http".equalsIgnoreCase(uri.getScheme())) {
                    errMsg = "Only support http proxy";
                    valid = false;
                } else if (uri.getHost() == null) {
                    errMsg = "host can not be null";
                    valid = false;
                } else if (uri.getPort() == -1) {
                    _httpProxy = _httpProxy + ":3128";
                }
            } catch (final URISyntaxException e) {
                errMsg = e.toString();
            } finally {
                if (!valid) {
                    s_logger.debug("ssvm http proxy " + _httpProxy + " is invalid: " + errMsg);
                    throw new ConfigurationException("ssvm http proxy " + _httpProxy + "is invalid: " + errMsg);
                }
            }
        }
        if (s_logger.isInfoEnabled()) {
            s_logger.info("Secondary storage vm Manager is configured.");
        }
        _resourceMgr.registerResourceStateAdapter(this.getClass().getSimpleName(), this);
        return true;
    }

    @Override
    public boolean start() {
        if (s_logger.isInfoEnabled()) {
            s_logger.info("Start secondary storage vm manager");
        }

        return true;
    }

    @Override
    public boolean stop() {
        _loadScanner.stop();
        _allocLock.releaseRef();
        _resourceMgr.unregisterResourceStateAdapter(this.getClass().getSimpleName());
        return true;
    }

    private String getAllocLockName() {
        // to improve security, it may be better to return a unique mashed
        // name(for example MD5 hashed)
        return "secStorageVm.alloc";
    }

    @Override
    public boolean finalizeVirtualMachineProfile(final VirtualMachineProfile profile, final DeployDestination dest, final ReservationContext context) {

        final SecondaryStorageVmVO vm = _secStorageVmDao.findById(profile.getId());
        final Map<String, String> details = _vmDetailsDao.listDetailsKeyPairs(vm.getId());
        vm.setDetails(details);

        final DataStore secStore = _dataStoreMgr.getImageStore(dest.getDataCenter().getId());
        assert (secStore != null);

        final StringBuilder buf = profile.getBootArgsBuilder();
        buf.append(" template=domP type=secstorage");
        buf.append(" host=").append(ApiServiceConfiguration.ManagementHostIPAdr.value());
        buf.append(" port=").append(_mgmtPort);
        buf.append(" name=").append(profile.getVirtualMachine().getHostName());

        buf.append(" zone=").append(dest.getDataCenter().getId());
        buf.append(" pod=").append(dest.getPod().getId());

        buf.append(" guid=").append(profile.getVirtualMachine().getHostName());

        buf.append(" workers=").append(_configDao.getValue("workers"));

        buf.append(" resource=org.apache.cloudstack.storage.resource.NfsSecondaryStorageResource");
        buf.append(" instance=SecStorage");
        buf.append(" sslcopy=").append(Boolean.toString(_useSSlCopy));
        buf.append(" role=").append(vm.getRole().toString());
        buf.append(" mtu=").append(_secStorageVmMtuSize);

        boolean externalDhcp = false;
        final String externalDhcpStr = _configDao.getValue("direct.attach.network.externalIpAllocator.enabled");
        if (externalDhcpStr != null && externalDhcpStr.equalsIgnoreCase("true")) {
            externalDhcp = true;
        }

        if (Boolean.valueOf(_configDao.getValue("system.vm.random.password"))) {
            buf.append(" vmpassword=").append(_configDao.getValue("system.vm.password"));
        }

        for (final NicProfile nic : profile.getNics()) {
            final int deviceId = nic.getDeviceId();
            if (nic.getIPv4Address() == null) {
                buf.append(" eth").append(deviceId).append("mask=").append("0.0.0.0");
                buf.append(" eth").append(deviceId).append("ip=").append("0.0.0.0");
            } else {
                buf.append(" eth").append(deviceId).append("ip=").append(nic.getIPv4Address());
                buf.append(" eth").append(deviceId).append("mask=").append(nic.getIPv4Netmask());
            }

            if (nic.isDefaultNic()) {
                buf.append(" gateway=").append(nic.getIPv4Gateway());
            }
            if (nic.getTrafficType() == TrafficType.Management) {
                final String mgmt_cidr = _configDao.getValue(Config.ManagementNetwork.key());
                if (NetUtils.isValidCIDR(mgmt_cidr)) {
                    buf.append(" mgmtcidr=").append(mgmt_cidr);
                }
                buf.append(" localgw=").append(dest.getPod().getGateway());
                buf.append(" private.network.device=").append("eth").append(deviceId);
            } else if (nic.getTrafficType() == TrafficType.Public) {
                buf.append(" public.network.device=").append("eth").append(deviceId);
            } else if (nic.getTrafficType() == TrafficType.Storage) {
                buf.append(" storageip=").append(nic.getIPv4Address());
                buf.append(" storagenetmask=").append(nic.getIPv4Netmask());
                buf.append(" storagegateway=").append(nic.getIPv4Gateway());
            }
        }

        /* External DHCP mode */
        if (externalDhcp) {
            buf.append(" bootproto=dhcp");
        }

        final DataCenterVO dc = _dcDao.findById(profile.getVirtualMachine().getDataCenterId());
        buf.append(" internaldns1=").append(dc.getInternalDns1());
        if (dc.getInternalDns2() != null) {
            buf.append(" internaldns2=").append(dc.getInternalDns2());
        }
        buf.append(" dns1=").append(dc.getDns1());
        if (dc.getDns2() != null) {
            buf.append(" dns2=").append(dc.getDns2());
        }

        final String bootArgs = buf.toString();
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Boot Args for " + profile + ": " + bootArgs);
        }

        return true;
    }

    @Override
    public boolean finalizeDeployment(final Commands cmds, final VirtualMachineProfile profile, final DeployDestination dest, final ReservationContext context) {

        finalizeCommandsOnStart(cmds, profile);

        final SecondaryStorageVmVO secVm = _secStorageVmDao.findById(profile.getId());
        final DataCenter dc = dest.getDataCenter();
        final List<NicProfile> nics = profile.getNics();
        computeVmIps(secVm, dc, nics);
        _secStorageVmDao.update(secVm.getId(), secVm);
        return true;
    }

    @Override
    public boolean finalizeStart(final VirtualMachineProfile profile, final long hostId, final Commands cmds, final ReservationContext context) {
        final CheckSshAnswer answer = (CheckSshAnswer) cmds.getAnswer("checkSsh");
        if (!answer.getResult()) {
            s_logger.warn("Unable to ssh to the VM: " + answer.getDetails());
            return false;
        }

        try {
            //get system ip and create static nat rule for the vm in case of basic networking with EIP/ELB
            _rulesMgr.getSystemIpAndEnableStaticNatForVm(profile.getVirtualMachine(), false);
            final IPAddressVO ipaddr = _ipAddressDao.findByAssociatedVmId(profile.getVirtualMachine().getId());
            if (ipaddr != null && ipaddr.getSystem()) {
                final SecondaryStorageVmVO secVm = _secStorageVmDao.findById(profile.getId());
                // override SSVM guest IP with EIP, so that download url's with be prepared with EIP
                secVm.setPublicIpAddress(ipaddr.getAddress().addr());
                _secStorageVmDao.update(secVm.getId(), secVm);
            }
        } catch (final Exception ex) {
            s_logger.warn("Failed to get system ip and enable static nat for the vm " + profile.getVirtualMachine() + " due to exception ", ex);
            return false;
        }

        return true;
    }

    @Override
    public boolean finalizeCommandsOnStart(final Commands cmds, final VirtualMachineProfile profile) {

        NicProfile managementNic = null;
        NicProfile controlNic = null;
        for (final NicProfile nic : profile.getNics()) {
            if (nic.getTrafficType() == TrafficType.Management) {
                managementNic = nic;
            } else if (nic.getTrafficType() == TrafficType.Control && nic.getIPv4Address() != null) {
                controlNic = nic;
            }
        }

        if (controlNic == null) {
            if (managementNic == null) {
                s_logger.error("Management network doesn't exist for the secondaryStorageVm " + profile.getVirtualMachine());
                return false;
            }
            controlNic = managementNic;
        }

        final CheckSshCommand check = new CheckSshCommand(profile.getInstanceName(), controlNic.getIPv4Address(), 3922);
        cmds.addCommand("checkSsh", check);

        return true;
    }

    @Override
    public void finalizeStop(final VirtualMachineProfile profile, final Answer answer) {
        //release elastic IP here
        finalizeStop(profile, _ipAddressDao.findByAssociatedVmId(profile.getId()), _rulesMgr);
    }

    @Override
    public void finalizeExpunge(final VirtualMachine vm) {
        final SecondaryStorageVmVO ssvm = _secStorageVmDao.findByUuid(vm.getUuid());

        ssvm.setPublicIpAddress(null);
        ssvm.setPublicMacAddress(null);
        ssvm.setPublicNetmask(null);
        _secStorageVmDao.update(ssvm.getId(), ssvm);
    }

    @Override
    public void prepareStop(final VirtualMachineProfile profile) {

    }

    @Override
    public String getScanHandlerName() {
        return "secstorage";
    }

    @Override
    public boolean canScan() {
        return true;
    }

    @Override
    public void onScanStart() {
        _zoneHostInfoMap = getZoneHostInfo();
    }

    private synchronized Map<Long, ZoneHostInfo> getZoneHostInfo() {
        return getLongZoneHostInfoMap(_hostDao);
    }

    @Override
    public Long[] getScannablePools() {
        return getLongs(_dcDao);
    }

    @Override
    public boolean isPoolReadyForScan(final Long pool) {
        // pool is at zone basis
        final long dataCenterId = pool.longValue();

        if (!isZoneReady(_zoneHostInfoMap, dataCenterId)) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Zone " + dataCenterId + " is not ready to launch secondary storage VM yet");
            }
            return false;
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Zone " + dataCenterId + " is ready to launch secondary storage VM");
        }
        return true;
    }

    public boolean isZoneReady(final Map<Long, ZoneHostInfo> zoneHostInfoMap, final long dataCenterId) {
        final ZoneHostInfo zoneHostInfo = zoneHostInfoMap.get(dataCenterId);
        if (zoneHostInfo != null && (zoneHostInfo.getFlags() & RunningHostInfoAgregator.ZoneHostInfo.ROUTING_HOST_MASK) != 0) {
            final VMTemplateVO template = _templateDao.findSystemVMReadyTemplate(dataCenterId, HypervisorType.Any);
            if (template == null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("System vm template is not ready at data center " + dataCenterId + ", wait until it is ready to launch secondary storage vm");
                }
                return false;
            }

            final List<DataStore> stores = _dataStoreMgr.getImageStoresByScope(new ZoneScope(dataCenterId));
            if (stores.size() < 1) {
                s_logger.debug("No image store added  in zone " + dataCenterId + ", wait until it is ready to launch secondary storage vm");
                return false;
            }

            final DataStore store = templateMgr.getImageStore(dataCenterId, template.getId());
            if (store == null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("No secondary storage available in zone " + dataCenterId + ", wait until it is ready to launch secondary storage vm");
                }
                return false;
            }

            boolean useLocalStorage = false;
            final Boolean useLocal = ConfigurationManagerImpl.SystemVMUseLocalStorage.valueIn(dataCenterId);
            if (useLocal != null) {
                useLocalStorage = useLocal.booleanValue();
            }
            final List<Pair<Long, Integer>> l = _storagePoolHostDao.getDatacenterStoragePoolHostInfo(dataCenterId, !useLocalStorage);
            if (l != null && l.size() > 0 && l.get(0).second().intValue() > 0) {
                return true;
            } else {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Primary storage is not ready, wait until it is ready to launch secondary storage vm. dcId: " + dataCenterId +
                            ", " + ConfigurationManagerImpl.SystemVMUseLocalStorage.key() + ": " + useLocalStorage + ". " +
                            "If you want to use local storage to start SSVM, need to set " + ConfigurationManagerImpl.SystemVMUseLocalStorage.key() + " to true");
                }
            }
        }
        return false;
    }

    @Override
    public Pair<AfterScanAction, Object> scanPool(final Long pool) {
        final long dataCenterId = pool.longValue();

        final List<SecondaryStorageVmVO> ssVms =
                _secStorageVmDao.getSecStorageVmListInStates(SecondaryStorageVm.Role.templateProcessor, dataCenterId, State.Running, State.Migrating, State.Starting,
                        State.Stopped, State.Stopping);
        final int vmSize = (ssVms == null) ? 0 : ssVms.size();
        final List<DataStore> ssStores = _dataStoreMgr.getImageStoresByScope(new ZoneScope(dataCenterId));
        final int storeSize = (ssStores == null) ? 0 : ssStores.size();
        if (storeSize > vmSize) {
            s_logger.info("No secondary storage vms found in datacenter id=" + dataCenterId + ", starting a new one");
            return new Pair<>(AfterScanAction.expand, SecondaryStorageVm.Role.templateProcessor);
        }

        return new Pair<>(AfterScanAction.nop, SecondaryStorageVm.Role.templateProcessor);
    }

    @Override
    public void expandPool(final Long pool, final Object actionArgs) {
        final long dataCenterId = pool.longValue();
        allocCapacity(dataCenterId, (SecondaryStorageVm.Role) actionArgs);
    }

    private void allocCapacity(final long dataCenterId, final SecondaryStorageVm.Role role) {
        if (s_logger.isTraceEnabled()) {
            s_logger.trace("Allocate secondary storage vm standby capacity for data center : " + dataCenterId);
        }

        if (!isSecondaryStorageVmRequired(dataCenterId)) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Secondary storage vm not required in zone " + dataCenterId + " according to zone config");
            }
            return;
        }
        SecondaryStorageVmVO secStorageVm = null;
        String errorString = null;
        try {
            boolean secStorageVmFromStoppedPool = false;
            secStorageVm = assignSecStorageVmFromStoppedPool(dataCenterId, role);
            if (secStorageVm == null) {
                if (s_logger.isInfoEnabled()) {
                    s_logger.info("No stopped secondary storage vm is available, need to allocate a new secondary storage vm");
                }

                if (_allocLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_SYNC)) {
                    try {
                        secStorageVm = startNew(dataCenterId, role);
                        for (final UploadVO upload : _uploadDao.listAll()) {
                            _uploadDao.expunge(upload.getId());
                        }
                    } finally {
                        _allocLock.unlock();
                    }
                } else {
                    if (s_logger.isInfoEnabled()) {
                        s_logger.info("Unable to acquire synchronization lock for secondary storage vm allocation, wait for next scan");
                    }
                    return;
                }
            } else {
                if (s_logger.isInfoEnabled()) {
                    s_logger.info("Found a stopped secondary storage vm, starting it. Vm id : " + secStorageVm.getId());
                }
                secStorageVmFromStoppedPool = true;
            }

            if (secStorageVm != null) {
                final long secStorageVmId = secStorageVm.getId();
                final GlobalLock secStorageVmLock = GlobalLock.getInternLock(getSecStorageVmLockName(secStorageVmId));
                try {
                    if (secStorageVmLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_SYNC)) {
                        try {
                            secStorageVm = startSecStorageVm(secStorageVmId);
                        } finally {
                            secStorageVmLock.unlock();
                        }
                    } else {
                        if (s_logger.isInfoEnabled()) {
                            s_logger.info("Unable to acquire synchronization lock for starting secondary storage vm id : " + secStorageVm.getId());
                        }
                        return;
                    }
                } finally {
                    secStorageVmLock.releaseRef();
                }

                if (secStorageVm == null) {
                    if (s_logger.isInfoEnabled()) {
                        s_logger.info("Unable to start secondary storage vm for standby capacity, vm id : " + secStorageVmId + ", will recycle it and start a new one");
                    }

                    if (secStorageVmFromStoppedPool) {
                        destroySecStorageVm(secStorageVmId);
                    }
                } else {
                    SubscriptionMgr.getInstance().notifySubscribers(ALERT_SUBJECT, this,
                            new SecStorageVmAlertEventArgs(SecStorageVmAlertEventArgs.SSVM_UP, dataCenterId, secStorageVmId, secStorageVm, null));
                    if (s_logger.isInfoEnabled()) {
                        s_logger.info("Secondary storage vm " + secStorageVm.getHostName() + " is started");
                    }
                }
            }
        } catch (final Exception e) {
            errorString = e.getMessage();
            throw e;
        } finally {
            // TODO - For now put all the alerts as creation failure. Distinguish between creation vs start failure in future.
            // Also add failure reason since startvm masks some of them.
            if (secStorageVm == null || secStorageVm.getState() != State.Running) {
                SubscriptionMgr.getInstance().notifySubscribers(ALERT_SUBJECT, this,
                        new SecStorageVmAlertEventArgs(SecStorageVmAlertEventArgs.SSVM_CREATE_FAILURE, dataCenterId, 0l, null, errorString));
            }
        }
    }

    protected boolean isSecondaryStorageVmRequired(final long dcId) {
        final DataCenterVO dc = _dcDao.findById(dcId);
        _dcDao.loadDetails(dc);
        final String ssvmReq = dc.getDetail(ZoneConfig.EnableSecStorageVm.key());
        if (ssvmReq != null) {
            return Boolean.parseBoolean(ssvmReq);
        }
        return true;
    }

    public SecondaryStorageVmVO assignSecStorageVmFromStoppedPool(final long dataCenterId, final SecondaryStorageVm.Role role) {
        final List<SecondaryStorageVmVO> l = _secStorageVmDao.getSecStorageVmListInStates(role, dataCenterId, State.Starting, State.Stopped, State.Migrating);
        if (l != null && l.size() > 0) {
            return l.get(0);
        }

        return null;
    }

    public SecondaryStorageVmVO startNew(final long dataCenterId, final SecondaryStorageVm.Role role) {

        if (!isSecondaryStorageVmRequired(dataCenterId)) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Secondary storage vm not required in zone " + dataCenterId + " acc. to zone config");
            }
            return null;
        }
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Assign secondary storage vm from a newly started instance for request from data center : " + dataCenterId);
        }

        final Map<String, Object> context = createSecStorageVmInstance(dataCenterId, role);

        final long secStorageVmId = (Long) context.get("secStorageVmId");
        if (secStorageVmId == 0) {
            if (s_logger.isTraceEnabled()) {
                s_logger.trace("Creating secondary storage vm instance failed, data center id : " + dataCenterId);
            }

            return null;
        }

        final SecondaryStorageVmVO secStorageVm = _secStorageVmDao.findById(secStorageVmId);
        if (secStorageVm != null) {
            SubscriptionMgr.getInstance().notifySubscribers(ALERT_SUBJECT, this,
                    new SecStorageVmAlertEventArgs(SecStorageVmAlertEventArgs.SSVM_CREATED, dataCenterId, secStorageVmId, secStorageVm, null));
            return secStorageVm;
        } else {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Unable to allocate secondary storage vm storage, remove the secondary storage vm record from DB, secondary storage vm id: " +
                        secStorageVmId);
            }
            SubscriptionMgr.getInstance().notifySubscribers(ALERT_SUBJECT, this,
                    new SecStorageVmAlertEventArgs(SecStorageVmAlertEventArgs.SSVM_CREATE_FAILURE, dataCenterId, secStorageVmId, null, "Unable to allocate storage"));
        }
        return null;
    }

    @Override
    public SecondaryStorageVmVO startSecStorageVm(final long secStorageVmId) {
        try {
            final SecondaryStorageVmVO secStorageVm = _secStorageVmDao.findById(secStorageVmId);
            _itMgr.advanceStart(secStorageVm.getUuid(), null, null);
            return _secStorageVmDao.findById(secStorageVm.getId());
        } catch (final StorageUnavailableException e) {
            s_logger.warn("Exception while trying to start secondary storage vm", e);
            return null;
        } catch (final InsufficientCapacityException e) {
            s_logger.warn("Exception while trying to start secondary storage vm", e);
            return null;
        } catch (final ResourceUnavailableException e) {
            s_logger.warn("Exception while trying to start secondary storage vm", e);
            return null;
        } catch (final Exception e) {
            s_logger.warn("Exception while trying to start secondary storage vm", e);
            return null;
        }
    }

    @Override
    public boolean stopSecStorageVm(final long secStorageVmId) {
        final SecondaryStorageVmVO secStorageVm = _secStorageVmDao.findById(secStorageVmId);
        if (secStorageVm == null) {
            final String msg = "Stopping secondary storage vm failed: secondary storage vm " + secStorageVmId + " no longer exists";
            if (s_logger.isDebugEnabled()) {
                s_logger.debug(msg);
            }
            return false;
        }
        try {
            if (secStorageVm.getHostId() != null) {
                final GlobalLock secStorageVmLock = GlobalLock.getInternLock(getSecStorageVmLockName(secStorageVm.getId()));
                try {
                    if (secStorageVmLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_SYNC)) {
                        try {
                            _itMgr.stop(secStorageVm.getUuid());
                            return true;
                        } finally {
                            secStorageVmLock.unlock();
                        }
                    } else {
                        final String msg = "Unable to acquire secondary storage vm lock : " + secStorageVm.toString();
                        s_logger.debug(msg);
                        return false;
                    }
                } finally {
                    secStorageVmLock.releaseRef();
                }
            }

            // vm was already stopped, return true
            return true;
        } catch (final ResourceUnavailableException e) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Stopping secondary storage vm " + secStorageVm.getHostName() + " faled : exception " + e.toString());
            }
            return false;
        }
    }

    @Override
    public boolean rebootSecStorageVm(final long secStorageVmId) {
        final SecondaryStorageVmVO secStorageVm = _secStorageVmDao.findById(secStorageVmId);

        if (secStorageVm == null || secStorageVm.getState() == State.Destroyed) {
            return false;
        }

        if (secStorageVm.getState() == State.Running && secStorageVm.getHostId() != null) {
            final RebootCommand cmd = new RebootCommand(secStorageVm.getInstanceName());
            final Answer answer = _agentMgr.easySend(secStorageVm.getHostId(), cmd);

            if (answer != null && answer.getResult()) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Successfully reboot secondary storage vm " + secStorageVm.getHostName());
                }

                SubscriptionMgr.getInstance().notifySubscribers(ALERT_SUBJECT, this,
                        new SecStorageVmAlertEventArgs(SecStorageVmAlertEventArgs.SSVM_REBOOTED, secStorageVm.getDataCenterId(), secStorageVm.getId(), secStorageVm, null));

                return true;
            } else {
                final String msg = "Rebooting Secondary Storage VM failed - " + secStorageVm.getHostName();
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug(msg);
                }
                return false;
            }
        } else {
            return startSecStorageVm(secStorageVmId) != null;
        }
    }

    @Override
    public boolean destroySecStorageVm(final long vmId) {
        final SecondaryStorageVmVO ssvm = _secStorageVmDao.findById(vmId);

        try {
            _itMgr.expunge(ssvm.getUuid());
            _secStorageVmDao.remove(ssvm.getId());
            final HostVO host = _hostDao.findByTypeNameAndZoneId(ssvm.getDataCenterId(), ssvm.getHostName(), Host.Type.SecondaryStorageVM);
            if (host != null) {
                s_logger.debug("Removing host entry for ssvm id=" + vmId);
                _hostDao.remove(host.getId());
                //Expire the download urls in the entire zone for templates and volumes.
                _tmplStoreDao.expireDnldUrlsForZone(host.getDataCenterId());
                _volumeStoreDao.expireDnldUrlsForZone(host.getDataCenterId());
                return true;
            }
            return false;
        } catch (final ResourceUnavailableException e) {
            s_logger.warn("Unable to expunge " + ssvm, e);
            return false;
        }
    }

    @Override
    public void onAgentConnect(final Long dcId, final StartupCommand cmd) {
    }

    @Override
    public boolean generateFirewallConfiguration(final Long ssAHostId) {
        if (ssAHostId == null) {
            return true;
        }
        final HostVO ssAHost = _hostDao.findById(ssAHostId);
        final SecondaryStorageVmVO thisSecStorageVm = _secStorageVmDao.findByInstanceName(ssAHost.getName());

        if (thisSecStorageVm == null) {
            s_logger.warn("secondary storage VM " + ssAHost.getName() + " doesn't exist");
            return false;
        }

        final String copyPort = _useSSlCopy ? "443" : Integer.toString(TemplateConstants.DEFAULT_TMPLT_COPY_PORT);
        final SecStorageFirewallCfgCommand thiscpc = new SecStorageFirewallCfgCommand(true);
        thiscpc.addPortConfig(thisSecStorageVm.getPublicIpAddress(), copyPort, true, TemplateConstants.DEFAULT_TMPLT_COPY_INTF);

        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        sc.and(sc.entity().getType(), Op.EQ, Host.Type.SecondaryStorageVM);
        sc.and(sc.entity().getStatus(), Op.IN, Status.Up, Status.Connecting);
        final List<HostVO> ssvms = sc.list();
        for (final HostVO ssvm : ssvms) {
            if (ssvm.getId() == ssAHostId) {
                continue;
            }
            final Answer answer = _agentMgr.easySend(ssvm.getId(), thiscpc);
            if (answer != null && answer.getResult()) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Successfully programmed firewall rules into SSVM " + ssvm.getName());
                }
            } else {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("failed to program firewall rules into secondary storage vm : " + ssvm.getName());
                }
                return false;
            }
        }

        final SecStorageFirewallCfgCommand allSSVMIpList = new SecStorageFirewallCfgCommand(false);
        for (final HostVO ssvm : ssvms) {
            if (ssvm.getId() == ssAHostId) {
                continue;
            }
            allSSVMIpList.addPortConfig(ssvm.getPublicIpAddress(), copyPort, true, TemplateConstants.DEFAULT_TMPLT_COPY_INTF);
        }

        final Answer answer = _agentMgr.easySend(ssAHostId, allSSVMIpList);
        if (answer != null && answer.getResult()) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Successfully programmed firewall rules into " + thisSecStorageVm.getHostName());
            }
        } else {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("failed to program firewall rules into secondary storage vm : " + thisSecStorageVm.getHostName());
            }
            return false;
        }

        return true;
    }

    @Override
    public boolean generateVMSetupCommand(final Long ssAHostId) {
        final HostVO ssAHost = _hostDao.findById(ssAHostId);
        if (ssAHost.getType() != Host.Type.SecondaryStorageVM) {
            return false;
        }
        final SecondaryStorageVmVO secStorageVm = _secStorageVmDao.findByInstanceName(ssAHost.getName());
        if (secStorageVm == null) {
            s_logger.warn("secondary storage VM " + ssAHost.getName() + " doesn't exist");
            return false;
        }

        final SecStorageVMSetupCommand setupCmd = new SecStorageVMSetupCommand();
        if (_allowedInternalSites != null) {
            final List<String> allowedCidrs = new ArrayList<>();
            final String[] cidrs = _allowedInternalSites.split(",");
            for (final String cidr : cidrs) {
                if (NetUtils.isValidCIDR(cidr) || NetUtils.isValidIp(cidr) || !cidr.startsWith("0.0.0.0")) {
                    allowedCidrs.add(cidr);
                }
            }
            setupCmd.setAllowedInternalSites(allowedCidrs.toArray(new String[allowedCidrs.size()]));
        }
        final String copyPasswd = _configDao.getValue("secstorage.copy.password");
        setupCmd.setCopyPassword(copyPasswd);
        setupCmd.setCopyUserName(TemplateConstants.DEFAULT_HTTP_AUTH_USER);
        final Answer answer = _agentMgr.easySend(ssAHostId, setupCmd);
        if (answer != null && answer.getResult()) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Successfully programmed http auth into " + secStorageVm.getHostName());
            }
            return true;
        } else {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("failed to program http auth into secondary storage vm : " + secStorageVm.getHostName());
            }
            return false;
        }
    }

    @Override
    public Pair<HostVO, SecondaryStorageVmVO> assignSecStorageVm(final long zoneId, final Command cmd) {
        return null;
    }

    @Override
    public boolean generateSetupCommand(final Long ssHostId) {
        final HostVO cssHost = _hostDao.findById(ssHostId);
        final Long zoneId = cssHost.getDataCenterId();
        if (cssHost.getType() == Host.Type.SecondaryStorageVM) {

            final SecondaryStorageVmVO secStorageVm = _secStorageVmDao.findByInstanceName(cssHost.getName());
            if (secStorageVm == null) {
                s_logger.warn("secondary storage VM " + cssHost.getName() + " doesn't exist");
                return false;
            }

            final List<DataStore> ssStores = _dataStoreMgr.getImageStoresByScope(new ZoneScope(zoneId));
            for (final DataStore ssStore : ssStores) {
                if (!(ssStore.getTO() instanceof NfsTO)) {
                    continue; // only do this for Nfs
                }
                final String secUrl = ssStore.getUri();
                SecStorageSetupCommand setupCmd = null;
                if (!_useSSlCopy) {
                    setupCmd = new SecStorageSetupCommand(ssStore.getTO(), secUrl, null);
                } else {
                    final KeystoreManager.Certificates certs = _keystoreMgr.getCertificates(ConsoleProxyManager.CERTIFICATE_NAME);
                    setupCmd = new SecStorageSetupCommand(ssStore.getTO(), secUrl, certs);
                }

                //template/volume file upload key
                final String postUploadKey = _configDao.getValue(Config.SSVMPSK.key());
                setupCmd.setPostUploadKey(postUploadKey);

                final Answer answer = _agentMgr.easySend(ssHostId, setupCmd);
                if (answer != null && answer.getResult()) {
                    final SecStorageSetupAnswer an = (SecStorageSetupAnswer) answer;
                    if (an.get_dir() != null) {
                        // update the parent path in image_store table for this image store
                        final ImageStoreVO svo = _imageStoreDao.findById(ssStore.getId());
                        svo.setParent(an.get_dir());
                        _imageStoreDao.update(ssStore.getId(), svo);
                    }
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Successfully programmed secondary storage " + ssStore.getName() + " in secondary storage VM " + secStorageVm.getInstanceName());
                    }
                } else {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Successfully programmed secondary storage " + ssStore.getName() + " in secondary storage VM " + secStorageVm.getInstanceName());
                    }
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public List<HostVO> listUpAndConnectingSecondaryStorageVmHost(final Long dcId) {
        final QueryBuilder<HostVO> sc = QueryBuilder.create(HostVO.class);
        if (dcId != null) {
            sc.and(sc.entity().getDataCenterId(), Op.EQ, dcId);
        }
        sc.and(sc.entity().getState(), Op.IN, Status.Up, Status.Connecting);
        sc.and(sc.entity().getType(), Op.EQ, Host.Type.SecondaryStorageVM);
        return sc.list();
    }

    @Override
    public HostVO pickSsvmHost(final HostVO ssHost) {
        if (ssHost.getType() == Host.Type.LocalSecondaryStorage) {
            return ssHost;
        } else if (ssHost.getType() == Host.Type.SecondaryStorage) {
            final Long dcId = ssHost.getDataCenterId();
            final List<HostVO> ssAHosts = listUpAndConnectingSecondaryStorageVmHost(dcId);
            if (ssAHosts == null || ssAHosts.isEmpty()) {
                return null;
            }
            Collections.shuffle(ssAHosts);
            return ssAHosts.get(0);
        }
        return null;
    }

    private String getSecStorageVmLockName(final long id) {
        return "secStorageVm." + id;
    }

    protected Map<String, Object> createSecStorageVmInstance(final long dataCenterId, final SecondaryStorageVm.Role role) {
        final DataStore secStore = _dataStoreMgr.getImageStore(dataCenterId);
        if (secStore == null) {
            final String msg = "No secondary storage available in zone " + dataCenterId + ", cannot create secondary storage vm";
            s_logger.warn(msg);
            throw new CloudRuntimeException(msg);
        }

        final long id = _secStorageVmDao.getNextInSequence(Long.class, "id");
        final String name = VirtualMachineName.getSystemVmName(id, _instance, "s").intern();
        final Account systemAcct = _accountMgr.getSystemAccount();

        final DataCenterDeployment plan = new DataCenterDeployment(dataCenterId);
        final DataCenter dc = _dcDao.findById(plan.getDataCenterId());

        final NetworkVO defaultNetwork = getDefaultNetworkForCreation(dc);

        List<? extends NetworkOffering> offerings = null;
        if (_sNwMgr.isStorageIpRangeAvailable(dataCenterId)) {
            offerings = _networkModel.getSystemAccountNetworkOfferings(NetworkOffering.SystemControlNetwork, NetworkOffering.SystemManagementNetwork, NetworkOffering
                    .SystemStorageNetwork);
        } else {
            offerings = _networkModel.getSystemAccountNetworkOfferings(NetworkOffering.SystemControlNetwork, NetworkOffering.SystemManagementNetwork);
        }
        final LinkedHashMap<Network, List<? extends NicProfile>> networks = new LinkedHashMap<>(offerings.size() + 1);
        final NicProfile defaultNic = new NicProfile();
        defaultNic.setDefaultNic(true);
        defaultNic.setDeviceId(2);
        try {
            networks.put(_networkMgr.setupNetwork(systemAcct, _networkOfferingDao.findById(defaultNetwork.getNetworkOfferingId()), plan, null, null, false).get(0),
                    new ArrayList<>(Arrays.asList(defaultNic)));
            for (final NetworkOffering offering : offerings) {
                networks.put(_networkMgr.setupNetwork(systemAcct, offering, plan, null, null, false).get(0), new ArrayList<>());
            }
        } catch (final ConcurrentOperationException e) {
            s_logger.info("Unable to setup due to concurrent operation. " + e);
            return new HashMap<>();
        }

        VMTemplateVO template = null;
        final HypervisorType availableHypervisor = _resourceMgr.getAvailableHypervisor(dataCenterId);
        template = _templateDao.findSystemVMReadyTemplate(dataCenterId, availableHypervisor);
        if (template == null) {
            throw new CloudRuntimeException("Not able to find the System templates or not downloaded in zone " + dataCenterId);
        }

        ServiceOfferingVO serviceOffering = _serviceOffering;
        if (serviceOffering == null) {
            serviceOffering = _offeringDao.findDefaultSystemOffering(ServiceOffering.ssvmDefaultOffUniqueName, ConfigurationManagerImpl.SystemVMUseLocalStorage.valueIn
                    (dataCenterId));
        }
        SecondaryStorageVmVO secStorageVm =
                new SecondaryStorageVmVO(id, serviceOffering.getId(), name, template.getId(), template.getHypervisorType(), template.getGuestOSId(), dataCenterId,
                        systemAcct.getDomainId(), systemAcct.getId(), _accountMgr.getSystemUser().getId(), role, serviceOffering.getOfferHA());
        secStorageVm.setDynamicallyScalable(template.isDynamicallyScalable());
        secStorageVm = _secStorageVmDao.persist(secStorageVm);
        try {
            _itMgr.allocate(name, template, serviceOffering, networks, plan, null);
            secStorageVm = _secStorageVmDao.findById(secStorageVm.getId());
        } catch (final InsufficientCapacityException e) {
            s_logger.warn("InsufficientCapacity", e);
            throw new CloudRuntimeException("Insufficient capacity exception", e);
        }

        final Map<String, Object> context = new HashMap<>();
        context.put("secStorageVmId", secStorageVm.getId());
        return context;
    }

    /**
     * Get the default network for the secondary storage VM, based on the zone it is in. Delegates to
     * either {@link #getDefaultNetworkForZone(DataCenter)} or {@link #getDefaultNetworkForAdvancedSGZone(DataCenter)},
     * depending on the zone network type and whether or not security groups are enabled in the zone.
     *
     * @param dc - The zone (DataCenter) of the secondary storage VM.
     * @return The default network for use with the secondary storage VM.
     */
    protected NetworkVO getDefaultNetworkForCreation(final DataCenter dc) {
        if (dc.getNetworkType() == NetworkType.Advanced) {
            return getDefaultNetworkForAdvancedZone(dc);
        } else {
            return getDefaultNetworkForBasicZone(dc);
        }
    }

    /**
     * Get default network for a secondary storage VM starting up in an advanced zone. If the zone
     * is security group-enabled, the first network found that supports SG services is returned.
     * If the zone is not SG-enabled, the Public network is returned.
     *
     * @param dc - The zone.
     * @return The selected default network.
     * @throws CloudRuntimeException - If the zone is not a valid choice or a network couldn't be found.
     */
    protected NetworkVO getDefaultNetworkForAdvancedZone(final DataCenter dc) {
        return getNetworkForAdvancedZone(dc, _networkDao);
    }

    /**
     * Get default network for secondary storage VM for starting up in a basic zone. Basic zones select
     * the Guest network whether or not the zone is SG-enabled.
     *
     * @param dc - The zone.
     * @return The default network according to the zone's network selection rules.
     * @throws CloudRuntimeException - If the zone is not a valid choice or a network couldn't be found.
     */
    protected NetworkVO getDefaultNetworkForBasicZone(final DataCenter dc) {
        return getNetworkForBasicZone(dc, _networkDao);
    }

    @Override
    public void shrinkPool(final Long pool, final Object actionArgs) {
    }

    @Override
    public void onScanEnd() {
    }

    @Override
    public HostVO createHostVOForConnectedAgent(final HostVO host, final StartupCommand[] cmd) {
        /* Called when Secondary Storage VM connected */
        final StartupCommand firstCmd = cmd[0];
        if (!(firstCmd instanceof StartupSecondaryStorageCommand)) {
            return null;
        }

        host.setType(com.cloud.host.Host.Type.SecondaryStorageVM);
        return host;
    }

    @Override
    public HostVO createHostVOForDirectConnectAgent(final HostVO host, final StartupCommand[] startup, final ServerResource resource, final Map<String, String> details,
                                                    final List<String> hostTags) {
        return null; // no need to handle this event anymore since secondary storage is not in host table anymore.
    }

    @Override
    public DeleteHostAnswer deleteHost(final HostVO host, final boolean isForced, final boolean isForceDeleteStorage) throws UnableDeleteHostException {
        // Since secondary storage is moved out of host table, this class should not handle delete secondary storage anymore.
        return null;
    }

    @Inject
    public void setSecondaryStorageVmAllocators(final List<SecondaryStorageVmAllocator> ssVmAllocators) {
    }
}