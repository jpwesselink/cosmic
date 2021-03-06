package org.apache.cloudstack.api.command.admin.vpc;

import org.apache.cloudstack.api.ApiCmdTestUtil;
import org.apache.cloudstack.api.ApiConstants;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class CreateVPCOfferingCmdTest {

    @Test
    public void testServiceProviders() throws IllegalArgumentException,
            IllegalAccessException {
        final CreateVPCOfferingCmd cmd = new CreateVPCOfferingCmd();
        final HashMap<String, Map<String, String>> providers = new HashMap<>();
        final HashMap<String, String> kv = new HashMap<>();
        kv.put("service", "TEST-SERVICE");
        kv.put("provider", "TEST-PROVIDER");
        providers.put("does not matter", kv);
        ApiCmdTestUtil.set(cmd, ApiConstants.SERVICE_PROVIDER_LIST, providers);
        final Map<String, List<String>> providerMap = cmd.getServiceProviders();
        Assert.assertNotNull(providerMap);
        Assert.assertEquals(1, providerMap.size());
        Assert.assertTrue(providerMap.containsKey("TEST-SERVICE"));
        Assert.assertTrue(providerMap.get("TEST-SERVICE").contains("TEST-PROVIDER"));
    }

    @Test
    public void testServiceProvidersEmpty() throws IllegalArgumentException,
            IllegalAccessException {
        final CreateVPCOfferingCmd cmd = new CreateVPCOfferingCmd();
        ApiCmdTestUtil.set(cmd, ApiConstants.SERVICE_PROVIDER_LIST, new HashMap<String, Map<String, String>>());
        Assert.assertNull(cmd.getServiceProviders());
    }

    @Test
    public void getDetailsNull() throws IllegalArgumentException,
            IllegalAccessException {
        final CreateVPCOfferingCmd cmd = new CreateVPCOfferingCmd();
        ApiCmdTestUtil.set(cmd, ApiConstants.SERVICE_PROVIDER_LIST, null);
        Assert.assertNull(cmd.getServiceProviders());
    }
}
