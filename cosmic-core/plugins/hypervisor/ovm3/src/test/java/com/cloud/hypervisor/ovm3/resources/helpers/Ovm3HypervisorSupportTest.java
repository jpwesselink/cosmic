package com.cloud.hypervisor.ovm3.resources.helpers;

import static org.junit.Assert.assertNull;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.FenceCommand;
import com.cloud.agent.api.GetHostStatsCommand;
import com.cloud.agent.api.GetVncPortCommand;
import com.cloud.agent.api.MaintainCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.hypervisor.ovm3.objects.ConnectionTest;
import com.cloud.hypervisor.ovm3.objects.LinuxTest;
import com.cloud.hypervisor.ovm3.objects.NetworkTest;
import com.cloud.hypervisor.ovm3.objects.Ovm3ResourceException;
import com.cloud.hypervisor.ovm3.objects.XenTest;
import com.cloud.hypervisor.ovm3.objects.XmlTestResultTest;
import com.cloud.hypervisor.ovm3.resources.Ovm3HypervisorResource;
import com.cloud.hypervisor.ovm3.resources.Ovm3StorageProcessor;
import com.cloud.hypervisor.ovm3.resources.Ovm3VirtualRoutingResource;
import com.cloud.hypervisor.ovm3.support.Ovm3SupportTest;
import com.cloud.vm.VirtualMachine.State;

import javax.naming.ConfigurationException;

import org.junit.Test;

public class Ovm3HypervisorSupportTest {
    ConnectionTest con = new ConnectionTest();
    XmlTestResultTest results = new XmlTestResultTest();
    Ovm3ConfigurationTest configTest = new Ovm3ConfigurationTest();
    Ovm3HypervisorResource hypervisor = new Ovm3HypervisorResource();
    Ovm3VirtualRoutingResource virtualrouting = new Ovm3VirtualRoutingResource();
    Ovm3SupportTest support = new Ovm3SupportTest();
    Ovm3StorageProcessor storage;
    Ovm3StoragePool pool;
    XenTest xen = new XenTest();
    String vmName = xen.getVmName();
    String unknown = "------";
    String running = "r-----";
    String blocked = "-b----";
    String paused = "--p---";
    String shutdown = "---s--";
    String crashed = "----c-";
    String dying = "-----d";

    /* we only want this for the xml results */
    String dom0stats = results.simpleResponseWrapWrapper("<struct>"
            + "<member>" + "<name>rx</name>"
            + "<value><string>25069761</string></value>" + "</member>"
            + "<member>" + "<name>total</name>"
            + "<value><string>4293918720</string></value>" + "</member>"
            + "<member>" + "<name>tx</name>"
            + "<value><string>37932556</string></value>" + "</member>"
            + "<member>" + "<name>cpu</name>"
            + "<value><string>2.4</string></value>" + "</member>" + "<member>"
            + "<name>free</name>"
            + "<value><string>1177550848</string></value>" + "</member>"
            + "</struct>");

    @Test
    public void ReportedVmStatesTest() throws ConfigurationException,
            Ovm3ResourceException {
        final Ovm3Configuration config = new Ovm3Configuration(configTest.getParams());
        con.setResult(xen.getMultipleVmsListXML());
        final Ovm3HypervisorSupport hypervisor = new Ovm3HypervisorSupport(con,
                config);
        hypervisor.vmStateMapClear();

        final State vmState = hypervisor.getVmState(vmName);
        results.basicStringTest(vmState.toString(), State.Running.toString());
        hypervisor.setVmStateStarting(vmName);
        results.basicStringTest(hypervisor.getVmState(vmName).toString(),
                State.Starting.toString());
        hypervisor.setVmState(vmName, State.Running);
        results.basicStringTest(hypervisor.getVmState(vmName).toString(),
                State.Running.toString());
        hypervisor.revmoveVmState(vmName);
        assertNull(hypervisor.getVmState(vmName));
    }

    @Test
    public void HypervisorVmStateTest() throws ConfigurationException,
            Ovm3ResourceException {
        final Ovm3Configuration config = new Ovm3Configuration(configTest.getParams());
        final Ovm3HypervisorSupport hypervisor = new Ovm3HypervisorSupport(con,
                config);
        setHypervisorVmState(hypervisor, blocked, unknown, State.Unknown);
        setHypervisorVmState(hypervisor, blocked, running, State.Running);
        setHypervisorVmState(hypervisor, blocked, blocked, State.Running);
        setHypervisorVmState(hypervisor, blocked, paused, State.Running);
    /* TODO: ehm wtf ? */
        setHypervisorVmState(hypervisor, blocked, shutdown, State.Running);
        setHypervisorVmState(hypervisor, blocked, crashed, State.Error);
        setHypervisorVmState(hypervisor, blocked, dying, State.Stopping);
    }

    /**
     * Sets the state, original, of the fake VM to replace.
     *
     * @param hypervisor
     * @param original
     * @param replace
     * @param state
     * @throws Ovm3ResourceException
     */
    public void setHypervisorVmState(final Ovm3HypervisorSupport hypervisor,
                                     final String original, final String replace, final State state)
            throws Ovm3ResourceException {
        final String x = xen.getMultipleVmsListXML().replaceAll(original, replace);
        con.setResult(x);
        hypervisor.syncState();
        results.basicStringTest(hypervisor.getVmState(vmName).toString(),
                state.toString());
    }

    @Test
    public void CombinedVmStateTest() throws ConfigurationException,
            Ovm3ResourceException {
        final Ovm3Configuration config = new Ovm3Configuration(configTest.getParams());
        con.setResult(xen.getMultipleVmsListXML());
        final Ovm3HypervisorSupport hypervisor = new Ovm3HypervisorSupport(con,
                config);
        hypervisor.vmStateMapClear();
    /* test starting */
        hypervisor.setVmState(vmName, State.Starting);
        // System.out.println(hypervisor.getVmState(vmName));
        hypervisor.syncState();
        // System.out.println(hypervisor.getVmState(vmName));

        // setHypervisorVmState(hypervisor, blocked, paused, State.Stopped);

        hypervisor.setVmState(vmName, State.Stopping);
        hypervisor.setVmState(vmName, State.Migrating);
        // setHypervisorVmState(hypervisor, blocked, running, State.Running);
        hypervisor.setVmState(vmName, State.Stopped);

        // setHypervisorVmState(hypervisor, blocked, running, State.Migrating);

    }

    @Test
    public void getSystemVMKeyFileTest() throws ConfigurationException {
        final Ovm3Configuration config = new Ovm3Configuration(configTest.getParams());
        final Ovm3HypervisorSupport hypervisor = new Ovm3HypervisorSupport(con,
                config);
        hypervisor.getSystemVmKeyFile(config.getAgentSshKeyFileName());
    }

    @Test
    public void getSystemVMKeyFileMissingTest() throws ConfigurationException {
        final Ovm3Configuration config = new Ovm3Configuration(configTest.getParams());
        final Ovm3HypervisorSupport hypervisor = new Ovm3HypervisorSupport(con,
                config);
        hypervisor.getSystemVmKeyFile("missing");
    }

    @Test
    public void checkHealthTest() throws ConfigurationException {
        con = prepare();
        final CheckHealthCommand cmd = new CheckHealthCommand();
        final Answer ra = hypervisor.executeRequest(cmd);
        results.basicBooleanTest(ra.getResult());
    }

    private ConnectionTest prepare() throws ConfigurationException {
        final Ovm3Configuration config = new Ovm3Configuration(configTest.getParams());
        con = support.prepConnectionResults();
        pool = new Ovm3StoragePool(con, config);
        storage = new Ovm3StorageProcessor(con, config, pool);
        hypervisor.setConnection(con);
        results.basicBooleanTest(hypervisor.configure(config.getAgentName(),
                configTest.getParams()));
        virtualrouting.setConnection(con);
        return con;
    }

    @Test
    public void masterCheckTest() throws ConfigurationException {
        con = prepare();
        // System.out.println(hypervisor.masterCheck());
    }

    @Test
    public void GetHostStatsCommandTest() throws ConfigurationException {
        con = prepare();
        final Ovm3Configuration config = new Ovm3Configuration(configTest.getParams());
        final GetHostStatsCommand cmd = new GetHostStatsCommand(config.getCsHostGuid(),
                config.getAgentName(), 1L);
        con.setResult(dom0stats);
        final Answer ra = hypervisor.executeRequest(cmd);
        results.basicBooleanTest(ra.getResult());
    }

    @Test
    public void GetHostStatsCommandFailTest() throws ConfigurationException {
        con = prepare();
        final Ovm3Configuration config = new Ovm3Configuration(configTest.getParams());
        final GetHostStatsCommand cmd = new GetHostStatsCommand(config.getCsHostGuid(),
                config.getAgentName(), 1L);
        con.setNull();
        final Answer ra = hypervisor.executeRequest(cmd);
        results.basicBooleanTest(ra.getResult(), false);
    }

    @Test
    public void CheckVirtualMachineCommandTest() throws ConfigurationException {
        con = prepare();
        final CheckVirtualMachineCommand cmd = new CheckVirtualMachineCommand(xen.getVmName());
        final Answer ra = hypervisor.executeRequest(cmd);
        results.basicBooleanTest(ra.getResult());
    }

    @Test
    public void MaintainCommandTest() throws ConfigurationException {
        con = prepare();
        final MaintainCommand cmd = new MaintainCommand();
        final Answer ra = hypervisor.executeRequest(cmd);
        results.basicBooleanTest(ra.getResult());
    }

    @Test
    public void GetVncPortCommandTest() throws ConfigurationException {
        con = prepare();
        final GetVncPortCommand cmd = new GetVncPortCommand(0, xen.getVmName());
        final Answer ra = hypervisor.executeRequest(cmd);
        results.basicBooleanTest(ra.getResult());
    }

    /* We can't fence yet... */
    @Test
    public void FenceCommandTest() throws ConfigurationException {
        con = prepare();
        final FenceCommand cmd = new FenceCommand();
        final Answer ra = hypervisor.executeRequest(cmd);
        results.basicBooleanTest(ra.getResult(), false);
    }

    @Test
    public void fillHostinfoTest() throws ConfigurationException {
        final Ovm3Configuration config = new Ovm3Configuration(configTest.getParams());
        final ConnectionTest con = new ConnectionTest();
        con.setIp(config.getAgentIp());
        final Ovm3HypervisorSupport hypervisor = new Ovm3HypervisorSupport(con,
                config);
        final LinuxTest linuxTest = new LinuxTest();
        final NetworkTest networkTest = new NetworkTest();
        final StartupRoutingCommand srCmd = new StartupRoutingCommand();
        con.setResult(results.simpleResponseWrapWrapper(linuxTest.getDiscoverHw()));
        con.addResult(results.simpleResponseWrapWrapper(linuxTest.getDiscoverserver()));
        con.addResult(results.simpleResponseWrapWrapper(networkTest.getDiscoverNetwork()));
        hypervisor.fillHostInfo(srCmd);
    }

  /*
   * @Test(expected = CloudRuntimeException.class) public void setupServerTest() throws ConfigurationException,
   * IOException { Ovm3Configuration config = new Ovm3Configuration(configTest.getParams()); ConnectionTest con = new
   * ConnectionTest(); con.setIp("127.0.0.1"); Ovm3HypervisorSupport hypervisor = new Ovm3HypervisorSupport(con,
   * config); hypervisor.setupServer(config.getAgentSshKeyFileName()); }
   */
}
