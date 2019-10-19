package org.openpnp.machine.openbuilds;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.swing.Action;

import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceHeadMountable;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.driver.AbstractReferenceDriver;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.util.Utils2D;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;

public class OpenBuildsDriver extends AbstractReferenceDriver implements Runnable {
    @Attribute(required = false)
    protected double feedRateMmPerMinute = 5000;

    @Attribute(required = false)
    private double zCamRadius = 24;

    @Attribute(required = false)
    private double zOffset = 17.5;

    @Attribute(required = false)
    private double backlashX = 2.0;

    @Attribute(required = false)
    private double backlashY = 2.0;
    
    @Attribute(required = false)
    private double backlashSpeed = 2000;    
    
    protected double x, y, zA, c, c2;
    private Thread readerThread;
    private boolean disconnectRequested;
    private Object commandLock = new Object();
    private boolean connected;
    private LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    private boolean n1Picked, n2Picked;

    @Override
    public void setEnabled(boolean enabled) throws Exception {
        if (enabled && !connected) {
            connect();
        }
        if (connected) {
            if (enabled) {
                sendCommand("M999");
                n1Vacuum(false);
                n2Vacuum(false);
                downLED(true);
            }
            else {
                sendCommand("M84");
                n1Vacuum(false);
                n2Vacuum(false);
                downLED(false);
                upLED(false);
            }
        }
        if (connected && !enabled) {
            if (!connectionKeepAlive) {
                disconnect();
            }
        }
    }

    @Override
    public void home(ReferenceHead head) throws Exception {
        
        //Reset any triggered limit switches
        sendCommand("M999");
        
        // We "home" Z by turning off the steppers, allowing the
        // spring to pull the nozzle back up to home.
        sendCommand("M84");
        Thread.sleep(250);
        // And call that zero
        sendCommand("G92 Z0");
        // Now move the head clockwise to ensure it is off of the microswitch
        sendCommand("G0 Z-4.5");
        // Once again let the head return to neutral
        sendCommand("M84");
        Thread.sleep(250);
        // Then send the home command
        sendCommand("G28 Z0", 10 * 1000);
        //And correct for the 5degree rotation offset
        sendCommand("G92 Z-0.5");

        // Home X and Y
        sendCommand("G28 X0 Y0", 60 * 1000);
        // Zero out the two "extruders"
        sendCommand("T1");
        sendCommand("G92 E0");
        sendCommand("T0");
        sendCommand("G92 E0");
        // Update position
        getCurrentPosition();
    }


    @Override
    public void actuate(ReferenceActuator actuator, boolean on) throws Exception {
        switch(actuator.getIndex()) {
            case 0: downLED(on);
                    break;
            case 1: n1Vacuum(on);
                    break;
            case 2: n2Vacuum(on);
                    break;
            case 3: upLED(on);
                    break;
        }
    }



    @Override
    public void actuate(ReferenceActuator actuator, double value) throws Exception {}


    @Override
    public Location getLocation(ReferenceHeadMountable hm) {
        if (hm instanceof ReferenceNozzle) {
            ReferenceNozzle nozzle = (ReferenceNozzle) hm;
            double z = Math.sin(Math.toRadians(this.zA)) * zCamRadius;
            if (getNozzleIndex(nozzle) == 0) {
                z = -z;
            }
            z += zOffset;
            int nozzleIndex = getNozzleIndex(nozzle);
            return new Location(LengthUnit.Millimeters, x, y, z,
                    Utils2D.normalizeAngle(nozzleIndex == 0 ? c : c2)).add(hm.getHeadOffsets());
        }
        else {
            return new Location(LengthUnit.Millimeters, x, y, zA, Utils2D.normalizeAngle(c))
                    .add(hm.getHeadOffsets());
        }
    }

    @Override
    public void moveTo(ReferenceHeadMountable hm, Location location, double speed)
            throws Exception {
        location = location.subtract(hm.getHeadOffsets());

        location = location.convertToUnits(LengthUnit.Millimeters);

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        double c = location.getRotation();

        ReferenceNozzle nozzle = null;
        if (hm instanceof ReferenceNozzle) {
            nozzle = (ReferenceNozzle) hm;
        }

        /*
         * Only move Z if it's a Nozzle.
         */
        if (nozzle == null) {
            z = Double.NaN;
        }

        StringBuffer sb = new StringBuffer();
        boolean backlashedX = false;
        boolean backlashedY = false;
        
        if (!Double.isNaN(x) && x != this.x) {
            if(x < this.x) //We are moving toward the origin
            {
                sb.append(String.format(Locale.US, "X%2.2f ", x-backlashX));
                this.x = x-backlashX;
                backlashedX = true;
            }
            else
            {
                sb.append(String.format(Locale.US, "X%2.2f ", x));
                this.x = x;
            }
        }
        if (!Double.isNaN(y) && y != this.y) {
            if(y > this.y) //We are moving toward the origin
            {
                sb.append(String.format(Locale.US, "Y%2.2f ", y+backlashY));
                this.y = y+backlashY;
                backlashedY = true;
            }
            else
            {
                sb.append(String.format(Locale.US, "Y%2.2f ", y));
                this.y = y;
            }
        }
        int nozzleIndex = getNozzleIndex(nozzle);
        double oldC = (nozzleIndex == 0 ? this.c : this.c2);
        if (!Double.isNaN(c) && c != oldC) {
            // Normalize the new angle.
            c = Utils2D.normalizeAngle(c);

            // Get the delta between the current position and the new position in normalized
            // degrees.
            double delta = c - Utils2D.normalizeAngle(oldC);

            // If the delta is greater than 180 we'll go the opposite direction instead to
            // minimize travel time.
            if (Math.abs(delta) > 180) {
                if (delta < 0) {
                    delta += 360;
                }
                else {
                    delta -= 360;
                }
            }

            c = oldC + delta;

            // If there is an E move we need to set the tool before
            // performing any commands otherwise we may move the wrong tool.
            sendCommand(String.format(Locale.US, "T%d", nozzleIndex));
            // We perform E moves solo because Smoothie doesn't like to make large E moves
            // with small X/Y moves, so we can't trust it to end up where we want it if we
            // do both at the same time.
            sendCommand(
                    String.format(Locale.US, "G0 E%2.2f F%2.2f", c, feedRateMmPerMinute * speed));
            dwell();
            if (nozzleIndex == 0) {
                this.c = c;
            }
            else {
                this.c2 = c;
            }
        }

        if (!Double.isNaN(z)) {
            if (z < 0.0) {
                z = 0;
            }
            double a = Math.toDegrees(Math.asin((z - zOffset) / zCamRadius));
            Logger.debug("nozzle {} {} {}", z, zCamRadius, a);
            if (a > 0) {
                a = 0;
            }
            if (nozzleIndex == 0) {
                a = -a;
            }
            if (a != this.zA) {
                this.zA = a;
                a/=10;
                sb.append(String.format(Locale.US, "Z%2.2f ", a));
            }
        }

        if (sb.length() > 0) {
            sb.append(String.format(Locale.US, "F%2.2f", feedRateMmPerMinute * speed));
            sendCommand("G0 " + sb.toString());
            
            if(backlashedX && backlashedY)
            {
                sendCommand(String.format(Locale.US, "G0 X%2.2f Y%2.2f F%2.2f", x, y, backlashSpeed));
                this.x = x;
                this.y = y;
            }
            if(backlashedX)
            {
                sendCommand(String.format(Locale.US, "G0 X%2.2f F%2.2f", x, backlashSpeed));
                this.x = x;
            }
            if(backlashedY)
            {
                sendCommand(String.format(Locale.US, "G0 Y%2.2f F%2.2f", y, backlashSpeed));
                this.y = y;
            }
            dwell();
        }
    }

    /**
     * Returns 0 or 1 for either the first or second Nozzle.
     * 
     * @param nozzle
     * @return
     */
    private int getNozzleIndex(Nozzle nozzle) {
        if (nozzle == null) {
            return 0;
        }
        return nozzle.getHead().getNozzles().indexOf(nozzle);
    }

    @Override
    public void pick(ReferenceNozzle nozzle) throws Exception {
        if (getNozzleIndex(nozzle) == 0) {
            n1Vacuum(true);
            n1Picked = true;
        }
        else {
            n2Vacuum(true);
            n2Picked = true;
        }
    }

    @Override
    public void place(ReferenceNozzle nozzle) throws Exception {
        if (getNozzleIndex(nozzle) == 0) {
            n1Picked = false;
            n1Vacuum(false);
        }
        else {
            n2Picked = false;
            n2Vacuum(false);
        }
    }

    public synchronized void connect() throws Exception {
        getCommunications().connect();

        /**
         * Connection process notes:
         * 
         * On some platforms, as soon as we open the serial port it will reset the controller and
         * we'll start getting some data. On others, it may already be running and we will get
         * nothing on connect.
         */

        connected = false;
        List<String> responses;
        readerThread = new Thread(this);
        readerThread.setDaemon(true);
        readerThread.start();

        try {
            do {
                // Consume any buffered incoming data, including startup messages
                responses = sendCommand(null, 200);
            } while (!responses.isEmpty());
        }
        catch (Exception e) {
            // ignore timeouts
        }


        // Send a request to force Smoothie to respond and clear any buffers.
        // On my machine, at least, this causes Smoothie to re-send it's
        // startup message and I can't figure out why, but this works
        // around it.
        responses = sendCommand("M114", 5000);
        // Continue to read responses until we get the one that is the
        // result of the M114 command. When we see that we're connected.
        long t = System.currentTimeMillis();
        while (System.currentTimeMillis() - t < 5000) {
            for (String response : responses) {
                if (response.contains("X:")) {
                    connected = true;
                    break;
                }
            }
            if (connected) {
                break;
            }
            responses = sendCommand(null, 200);
        }

        if (!connected) {
            throw new Exception(String.format(
                    "Unable to receive connection response. Check your port and baud rate"));
        }

        // We are connected to at least the minimum required version now
        // So perform some setup

        // Turn off the stepper drivers
        setEnabled(false);

        // Set mm coordinate mode
        sendCommand("G21");
        // Set absolute positioning mode
        sendCommand("G90");
        // Set absolute mode for extruder
        sendCommand("M82");
        getCurrentPosition();
    }

    protected void getCurrentPosition() throws Exception {
        List<String> responses;
        sendCommand("T0");
        responses = sendCommand("M114");
        for (String response : responses) {
            if (response.contains("X:")) {
                String[] comps = response.split(" ");
                for (String comp : comps) {
                    if (comp.startsWith("X:")) {
                        x = Double.parseDouble(comp.split(":")[1]);
                    }
                    else if (comp.startsWith("Y:")) {
                        y = Double.parseDouble(comp.split(":")[1]);
                    }
                    else if (comp.startsWith("Z:")) {
                        zA = Double.parseDouble(comp.split(":")[1]);
                        zA*=10;
                    }
                    else if (comp.startsWith("E:")) {
                        c = Double.parseDouble(comp.split(":")[1]);
                    }
                }
            }
        }
        sendCommand("T1");
        responses = sendCommand("M114");
        for (String response : responses) {
            if (response.contains("X:")) {
                String[] comps = response.split(" ");
                for (String comp : comps) {
                    if (comp.startsWith("E:")) {
                        c2 = Double.parseDouble(comp.split(":")[1]);
                    }
                }
            }
        }
        sendCommand("T0");

        Logger.debug("Current Position is {}, {}, {}, {}, {}", x, y, zA, c, c2);
    }

    public synchronized void disconnect() {
        disconnectRequested = true;
        connected = false;

        try {
            if (readerThread != null && readerThread.isAlive()) {
                readerThread.join(3000);
            }
        }
        catch (Exception e) {
            Logger.error("disconnect()", e);
        }

        try {
            getCommunications().disconnect();
        }
        catch (Exception e) {
            Logger.error("disconnect()", e);
        }
        disconnectRequested = false;
    }

    protected List<String> sendCommand(String command) throws Exception {
        return sendCommand(command, 5000);
    }

    protected List<String> sendCommand(String command, long timeout) throws Exception {
        List<String> responses = new ArrayList<>();

        // Read any responses that might be queued up so that when we wait
        // for a response to a command we actually wait for the one we expect.
        responseQueue.drainTo(responses);

        // Send the command, if one was specified
        if (command != null) {
            Logger.debug("sendCommand({}, {})", command, timeout);
            Logger.debug(">> " + command);
            getCommunications().writeLine(command);
        }

        String response = null;
        if (timeout == -1) {
            // Wait forever for a response to return from the reader.
            response = responseQueue.take();
        }
        else {
            // Wait up to timeout milliseconds for a response to return from
            // the reader.
            response = responseQueue.poll(timeout, TimeUnit.MILLISECONDS);
            if (response == null) {
                throw new Exception("Timeout waiting for response to " + command);
            }
        }
        // And if we got one, add it to the list of responses we'll return.
        responses.add(response);

        // Read any additional responses that came in after the initial one.
        responseQueue.drainTo(responses);

        Logger.debug("{} => {}", command, responses);
        return responses;
    }

    public void run() {
        while (!disconnectRequested) {
            String line;
            try {
                line = getCommunications().readLine().trim();
            }
            catch (TimeoutException ex) {
                continue;
            }
            catch (IOException e) {
                Logger.error("Read error", e);
                return;
            }
            line = line.trim();
            Logger.debug("<< " + line);
            responseQueue.offer(line);
            if (line.startsWith("ok") || line.startsWith("error: ")) {
                // This is the end of processing for a command
                synchronized (commandLock) {
                    commandLock.notify();
                }
            }
        }
    }

    /**
     * Block until all movement is complete.
     * 
     * @throws Exception
     */
    protected void dwell() throws Exception {
        sendCommand("M400");
    }

    private List<String> drainResponseQueue() {
        List<String> responses = new ArrayList<>();
        String response;
        while ((response = responseQueue.poll()) != null) {
            responses.add(response);
        }
        return responses;
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return getClass().getSimpleName();
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return null;
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        return new PropertySheet[] {new PropertySheetWizardAdapter(getConfigurationWizard())};
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new OpenBuildsDriverWizard(this);
    }

    private void n1Vacuum(boolean on) throws Exception {
        sendCommand(on ? "M800" : "M801");
    }

    private void n2Vacuum(boolean on) throws Exception {
        sendCommand(on ? "M802" : "M803");
    }

    private void downLED(boolean on) throws Exception {
        sendCommand(on ? "M804" : "M805");
    }
    
    private void upLED(boolean on) throws Exception {
        sendCommand(on ? "M806" : "M807");
    }
}
