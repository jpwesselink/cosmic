package com.cloud.hypervisor.xenserver.resource.wrapper.xenbase;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ScaleVmAnswer;
import com.cloud.agent.api.ScaleVmCommand;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.hypervisor.xenserver.resource.CitrixResourceBase;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.exception.CloudRuntimeException;

import java.util.Iterator;
import java.util.Set;

import com.xensource.xenapi.Connection;
import com.xensource.xenapi.Host;
import com.xensource.xenapi.Types.VmPowerState;
import com.xensource.xenapi.Types.XenAPIException;
import com.xensource.xenapi.VM;
import org.apache.xmlrpc.XmlRpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ResourceWrapper(handles = ScaleVmCommand.class)
public final class CitrixScaleVmCommandWrapper extends CommandWrapper<ScaleVmCommand, Answer, CitrixResourceBase> {

    private static final Logger s_logger = LoggerFactory.getLogger(CitrixScaleVmCommandWrapper.class);

    @Override
    public Answer execute(final ScaleVmCommand command, final CitrixResourceBase citrixResourceBase) {
        final VirtualMachineTO vmSpec = command.getVirtualMachine();
        final String vmName = vmSpec.getName();
        try {
            final Connection conn = citrixResourceBase.getConnection();
            final Set<VM> vms = VM.getByNameLabel(conn, vmName);
            final Host host = Host.getByUuid(conn, citrixResourceBase.getHost().getUuid());

            // If DMC is not enable then don't execute this command.
            if (!citrixResourceBase.isDmcEnabled(conn, host)) {
                throw new CloudRuntimeException("Unable to scale the vm: " + vmName + " as DMC - Dynamic memory control is not enabled for the XenServer:"
                        + citrixResourceBase.getHost().getUuid() + " ,check your license and hypervisor version.");
            }

            if (vms == null || vms.size() == 0) {
                s_logger.info("No running VM " + vmName + " exists on XenServer" + citrixResourceBase.getHost().getUuid());
                return new ScaleVmAnswer(command, false, "VM does not exist");
            }

            // stop vm which is running on this host or is in halted state
            final Iterator<VM> iter = vms.iterator();
            while (iter.hasNext()) {
                final VM vm = iter.next();
                final VM.Record vmr = vm.getRecord(conn);

                if (vmr.powerState == VmPowerState.HALTED || vmr.powerState == VmPowerState.RUNNING && !citrixResourceBase.isRefNull(vmr.residentOn)
                        && !vmr.residentOn.getUuid(conn).equals(citrixResourceBase.getHost().getUuid())) {
                    iter.remove();
                }
            }

            for (final VM vm : vms) {
                vm.getRecord(conn);
                try {
                    citrixResourceBase.scaleVM(conn, vm, vmSpec, host);
                } catch (final Exception e) {
                    final String msg = "Catch exception " + e.getClass().getName() + " when scaling VM:" + vmName + " due to " + e.toString();
                    s_logger.debug(msg);
                    return new ScaleVmAnswer(command, false, msg);
                }
            }
            final String msg = "scaling VM " + vmName + " is successful on host " + host;
            s_logger.debug(msg);
            return new ScaleVmAnswer(command, true, msg);
        } catch (final XenAPIException e) {
            final String msg = "Upgrade Vm " + vmName + " fail due to " + e.toString();
            s_logger.warn(msg, e);
            return new ScaleVmAnswer(command, false, msg);
        } catch (final XmlRpcException e) {
            final String msg = "Upgrade Vm " + vmName + " fail due to " + e.getMessage();
            s_logger.warn(msg, e);
            return new ScaleVmAnswer(command, false, msg);
        } catch (final Exception e) {
            final String msg = "Unable to upgrade " + vmName + " due to " + e.getMessage();
            s_logger.warn(msg, e);
            return new ScaleVmAnswer(command, false, msg);
        }
    }
}
