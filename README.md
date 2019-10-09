![OpenPNP Logo](https://raw.githubusercontent.com/openpnp/openpnp-logo/develop/logo_small.png)

# OpenPnP

Open Source SMT Pick and Place Hardware and Software

## What is this fork?

So I decided to build a pick and place machine.  Originally based on the [Openbuilds Reference Design](https://github.com/openpnp/openpnp-openbuilds/wiki/Build-Instructions), I've made some modifications to my machine, enough that I needed to [customize the openbuilds driver as a result](https://github.com/ChrisPVille/openpnp/blob/openpnpMachineBuild_develop/src/main/java/org/openpnp/machine/openbuilds/OpenBuildsDriver.java).  The big differences are:

* Larger build area at 750x750mm usable by at least one head
* Single larger Y axis motor using a driveshaft to ensure the axis stays aligned
* Industrial vacuum solenoids and vacuum accumulator to reduce dwell times to under 100ms
* External stepper motor drivers and higher operating voltage/current with peak movement speeds over 100000 mm/m (1.6 m/s)
* Software backlash compensation (which the openbuilds driver never got)

Altogether the result is around 0.1mm absolute accuracy and >2500CPH when not using vision.

For now the work is fine-tuning the machine, adjusting the driver for OpenPnP 2.0, and getting everything mounted in its permanent home.
