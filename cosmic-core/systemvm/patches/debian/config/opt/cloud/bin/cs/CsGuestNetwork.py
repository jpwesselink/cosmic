from merge import DataBag


class CsGuestNetwork:
    def __init__(self, device, config):
        self.data = { }
        self.guest = True
        db = DataBag()
        db.setKey("guestnetwork")
        db.load()
        dbag = db.getDataBag()
        self.config = config
        if device in dbag.keys() and len(dbag[device]) != 0:
            self.data = dbag[device][0]
        else:
            self.guest = False

    def is_guestnetwork(self):
        return self.guest

    def get_dns(self):
        if not self.guest:
            return self.config.get_dns()
        # Can a router provide dhcp but not dns?
        if 'dns' in self.data and 'router_guest_gateway' in self.data:
            return [self.data['router_guest_gateway']] + self.data['dns'].split(',')
        elif "router_guest_gateway" in self.data:
            return [self.data['router_guest_gateway']]
        else:
            return [""]

    def set_dns(self, val):
        self.data['dns'] = val

    def set_router(self, val):
        self.data['router_guest_gateway'] = val

    def get_netmask(self):
        # We need to fix it properly. I just added the if, as Ian did in some other files, to avoid the exception.
        if 'router_guest_netmask' in self.data:
            return self.data['router_guest_netmask']
        return ''

    def get_gateway(self):
        # We need to fix it properly. I just added the if, as Ian did in some other files, to avoid the exception.
        if 'router_guest_gateway' in self.data:
            return self.data['router_guest_gateway']
        return ''

    def get_domain(self):
        domain = "cloudnine.internal"
        if not self.guest:
            return self.config.get_domain()

        if 'domain_name' in self.data:
            return self.data['domain_name']

        return domain
