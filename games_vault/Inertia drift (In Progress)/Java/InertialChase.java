import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Inertial-Chase. Single-file Java arcade racer.
 *
 * Controls:
 *   Keyboard:    W/S accel/brake   A/D steer (left stick)   J/L drift (right stick)
 *   Xbox pad:    RT accel  LT brake  L stick steer  R stick drift
 *
 * Controller works on plain Java 21 by spawning a tiny PowerShell helper that
 * calls Win32 XInput via P/Invoke and pipes stick/trigger state back over stdout.
 */
public class InertialChase extends JPanel implements Runnable {

    // ====================== CONSTANTS ======================
    static final int WIDTH        = 1920;
    static final int HEIGHT       = 1080;
    static final int CENTER_X     = WIDTH / 2;
    static final int CENTER_Y     = HEIGHT / 2;
    static final double FOCAL     = 520.0;
    static final int TARGET_FPS   = 60;
    static final double FRAME_NS  = 1_000_000_000.0 / TARGET_FPS;

    static final double MAX_SPEED       = 460.0;
    static final double ACCEL           = 290.0;
    static final double BRAKE           = 430.0;
    static final double NATURAL_DECEL   = 60.0;
    static final double STEER_RATE      = 9.0;
    static final double STEER_INFLUENCE = 3.4;
    static final double DRIFT_RATE      = 6.0;
    static final double GRIP_NORMAL     = 6.5;
    static final double GRIP_DRIFTING   = 0.22;
    static final double DEADZONE        = 0.08;

    static final double CAM_HEIGHT     = 38.0;
    static final double CAM_BEHIND     = 100.0;
    static final int    DRAW_DISTANCE  = 280;
    static final double SEGMENT_LENGTH = 60.0;
    static final double ROAD_WIDTH     = 135.0;

    static final double FOG_START      = 80;    // segments
    static final double FOG_END        = 260;

    // ====================== GAME STATE ======================
    private Thread gameThread;
    private volatile boolean running = false;
    private Car player;
    private Track track;
    private Renderer renderer;
    private Input input;
    private final List<Car> aiCars = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();

    private int frames = 0;
    private int fps = 0;

    public InertialChase() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(Color.BLACK);
        setFocusable(true);

        input = new Input();
        addKeyListener(input);

        track = new Track();
        track.build();

        player = new Car(true);
        player.color = new Color(225, 55, 85);
        player.position[2] = 5 * SEGMENT_LENGTH;

        Color[] aiColors = {
            new Color( 80, 220, 255),
            new Color(255, 215,  60),
            new Color(170,  80, 255),
            new Color( 60, 240, 140),
        };
        for (int i = 0; i < 4; i++) {
            Car ai = new Car(false);
            ai.color = aiColors[i];
            ai.position[0] = ((i % 3) - 1) * 34;
            ai.position[2] = (18 + i * 14) * SEGMENT_LENGTH;
            aiCars.add(ai);
        }

        renderer = new Renderer();
    }

    public void start() {
        running = true;
        gameThread = new Thread(this, "GameLoop");
        gameThread.start();
    }

    @Override
    public void run() {
        long last = System.nanoTime();
        double accum = 0;
        long lastFpsCheck = System.currentTimeMillis();

        while (running) {
            long now = System.nanoTime();
            accum += (now - last) / FRAME_NS;
            last = now;

            while (accum >= 1) {
                update(1.0 / TARGET_FPS);
                accum--;
            }

            repaint();
            frames++;

            long ms = System.currentTimeMillis();
            if (ms - lastFpsCheck >= 1000) {
                fps = frames;
                frames = 0;
                lastFpsCheck = ms;
            }

            try { Thread.sleep(1); } catch (InterruptedException ex) {}
        }
    }

    private void update(double dt) {
        input.poll();
        player.updatePlayer(input, track, particles, dt);
        for (Car ai : aiCars) ai.updateAI(track, player, dt);

        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0) { it.remove(); continue; }
            p.z   += p.vz  * dt;
            p.dx  += p.vdx * dt;
            p.dy  += p.vdy * dt;
            p.size += p.growth * dt;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        renderer.render(g2, player, track, aiCars, particles, fps, input);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Inertial-Chase");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);

            InertialChase game = new InertialChase();
            frame.add(game);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            game.requestFocusInWindow();
            game.start();
        });
    }

    // ====================== CONTROLLER (PowerShell + Win32 XInput bridge) ======================
    static class Controller {
        private Process process;
        private Thread reader;
        volatile boolean spawned = false;
        volatile boolean connected = false;
        volatile String statusMsg = "starting helper...";

        volatile double lx, ly, rx, ry, lt, rt;

        // PowerShell script: declares Win32 XInput via P/Invoke, polls every 16ms,
        // writes one space-separated line of axis values per tick to stdout.
        private static final String PS_SCRIPT =
            "$ErrorActionPreference='Stop'\r\n" +
            "try {\r\n" +
            "  Add-Type -TypeDefinition @'\r\n" +
            "using System;\r\n" +
            "using System.Runtime.InteropServices;\r\n" +
            "public static class XI {\r\n" +
            "  [StructLayout(LayoutKind.Sequential)] public struct PAD {\r\n" +
            "    public ushort buttons; public byte lt; public byte rt;\r\n" +
            "    public short lx; public short ly; public short rx; public short ry;\r\n" +
            "  }\r\n" +
            "  [StructLayout(LayoutKind.Sequential)] public struct ST {\r\n" +
            "    public uint pkt; public PAD pad;\r\n" +
            "  }\r\n" +
            "  [DllImport(\"XInput1_4.dll\", EntryPoint=\"XInputGetState\")] public static extern int G14(int i, ref ST s);\r\n" +
            "  [DllImport(\"XInput1_3.dll\", EntryPoint=\"XInputGetState\")] public static extern int G13(int i, ref ST s);\r\n" +
            "  [DllImport(\"XInput9_1_0.dll\", EntryPoint=\"XInputGetState\")] public static extern int G91(int i, ref ST s);\r\n" +
            "}\r\n" +
            "'@\r\n" +
            "} catch { Write-Host (\"ERR addtype: \" + $_.Exception.Message); exit 1 }\r\n" +
            "\r\n" +
            "$state = New-Object XI+ST\r\n" +
            "$mode = 0\r\n" +
            "foreach ($m in 14,13,91) {\r\n" +
            "  try {\r\n" +
            "    if ($m -eq 14) { [XI]::G14(0,[ref]$state) | Out-Null }\r\n" +
            "    elseif ($m -eq 13) { [XI]::G13(0,[ref]$state) | Out-Null }\r\n" +
            "    else { [XI]::G91(0,[ref]$state) | Out-Null }\r\n" +
            "    $mode = $m\r\n" +
            "    break\r\n" +
            "  } catch {}\r\n" +
            "}\r\n" +
            "if ($mode -eq 0) { Write-Host 'ERR no_xinput_dll'; exit 1 }\r\n" +
            "Write-Host (\"READY mode=$mode\")\r\n" +
            "\r\n" +
            "while ($true) {\r\n" +
            "  try {\r\n" +
            "    if ($mode -eq 14) { $r = [XI]::G14(0,[ref]$state) }\r\n" +
            "    elseif ($mode -eq 13) { $r = [XI]::G13(0,[ref]$state) }\r\n" +
            "    else { $r = [XI]::G91(0,[ref]$state) }\r\n" +
            "  } catch { Write-Host ('ERR call: ' + $_.Exception.Message); break }\r\n" +
            "  if ($r -eq 0) {\r\n" +
            "    $p = $state.pad\r\n" +
            "    Write-Host ('C ' + $p.lx + ' ' + $p.ly + ' ' + $p.rx + ' ' + $p.ry + ' ' + $p.lt + ' ' + $p.rt + ' ' + $p.buttons)\r\n" +
            "  } else { Write-Host 'X' }\r\n" +
            "  Start-Sleep -Milliseconds 16\r\n" +
            "}\r\n";

        Controller() {
            try {
                Path script = Files.createTempFile("ic-pad-", ".ps1");
                script.toFile().deleteOnExit();
                Files.writeString(script, PS_SCRIPT);

                ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile", "-NoLogo",
                    "-ExecutionPolicy", "Bypass",
                    "-File", script.toString());
                pb.redirectErrorStream(true);
                process = pb.start();
                spawned = true;

                reader = new Thread(this::readLoop, "PadReader");
                reader.setDaemon(true);
                reader.start();

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { if (process != null) process.destroyForcibly(); } catch (Throwable t) {}
                }));
            } catch (Throwable t) {
                spawned = false;
                statusMsg = "helper spawn failed: " + t.getClass().getSimpleName();
            }
        }

        private void readLoop() {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    handleLine(line);
                }
            } catch (Throwable t) {
                statusMsg = "reader died: " + t.getClass().getSimpleName();
            }
            connected = false;
        }

        private void handleLine(String line) {
            if (line.length() == 0) return;
            char c0 = line.charAt(0);
            if (c0 == 'C') {
                String[] parts = line.split(" ");
                if (parts.length >= 7) {
                    try {
                        int sLX = Integer.parseInt(parts[1]);
                        int sLY = Integer.parseInt(parts[2]);
                        int sRX = Integer.parseInt(parts[3]);
                        int sRY = Integer.parseInt(parts[4]);
                        int sLT = Integer.parseInt(parts[5]);
                        int sRT = Integer.parseInt(parts[6]);
                        lx = normalizeStick(sLX, 7849);
                        ly = normalizeStick(sLY, 7849);
                        rx = normalizeStick(sRX, 8689);
                        ry = normalizeStick(sRY, 8689);
                        lt = normalizeTrigger(sLT);
                        rt = normalizeTrigger(sRT);
                        connected = true;
                        statusMsg = "controller connected";
                    } catch (NumberFormatException nfe) {}
                }
            } else if (c0 == 'X') {
                connected = false;
                lx = ly = rx = ry = lt = rt = 0;
                statusMsg = "waiting for controller";
            } else if (line.startsWith("READY")) {
                statusMsg = "helper ready, waiting for pad";
            } else if (line.startsWith("ERR")) {
                statusMsg = line;
            }
        }

        static double normalizeStick(int raw, int deadzone) {
            if (Math.abs(raw) < deadzone) return 0;
            double max = raw < 0 ? 32768.0 : 32767.0;
            double sign = raw < 0 ? -1 : 1;
            double mag = (Math.abs(raw) - deadzone) / (max - deadzone);
            return sign * Math.min(1.0, mag);
        }
        static double normalizeTrigger(int raw) {
            if (raw < 30) return 0;
            return (raw - 30) / (255.0 - 30);
        }
    }

    // ====================== INPUT (controller + keyboard) ======================
    static class Input extends KeyAdapter {
        private final boolean[] keys = new boolean[256];
        final Controller controller;

        double leftStickX  = 0;
        double rightStickX = 0;
        double accel       = 0;
        double brake       = 0;
        boolean usingController = false;

        Input() {
            Controller c;
            try { c = new Controller(); } catch (Throwable t) { c = null; }
            this.controller = c;
        }

        void poll() {
            // Keyboard
            double kLx = 0;
            if (keys[KeyEvent.VK_A] || keys[KeyEvent.VK_LEFT])  kLx -= 1.0;
            if (keys[KeyEvent.VK_D] || keys[KeyEvent.VK_RIGHT]) kLx += 1.0;
            double kRx = 0;
            if (keys[KeyEvent.VK_J]) kRx -= 1.0;
            if (keys[KeyEvent.VK_L]) kRx += 1.0;
            double kAccel = (keys[KeyEvent.VK_W] || keys[KeyEvent.VK_UP])   ? 1.0 : 0.0;
            double kBrake = (keys[KeyEvent.VK_S] || keys[KeyEvent.VK_DOWN]) ? 1.0 : 0.0;

            boolean padOn = controller != null && controller.connected;
            usingController = padOn;

            if (padOn) {
                leftStickX  = (Math.abs(controller.lx) > DEADZONE) ? controller.lx : kLx;
                rightStickX = (Math.abs(controller.rx) > DEADZONE) ? controller.rx : kRx;
                accel = Math.max(controller.rt, kAccel);
                brake = Math.max(controller.lt, kBrake);
            } else {
                leftStickX = kLx;
                rightStickX = kRx;
                accel = kAccel;
                brake = kBrake;
            }
        }

        @Override public void keyPressed(KeyEvent e) {
            int c = e.getKeyCode();
            if (c >= 0 && c < keys.length) keys[c] = true;
        }
        @Override public void keyReleased(KeyEvent e) {
            int c = e.getKeyCode();
            if (c >= 0 && c < keys.length) keys[c] = false;
        }
    }

    // ====================== CAR ======================
    static class Car {
        double[] position = new double[3];

        double heading = 0;
        double steeringAngle = 0;
        double vx = 0, vz = 0;
        double speed = 0;
        double driftCharge = 0;
        boolean wasOnGas = false;
        double slipAmount = 0;

        Color color = new Color(220, 220, 220);
        boolean isPlayer;

        int lap = 1;
        double totalDistance = 0;

        Car(boolean isPlayer) { this.isPlayer = isPlayer; }

        void updatePlayer(Input in, Track track, List<Particle> particles, double dt) {
            double lx = deadzone(in.leftStickX);
            double rx = deadzone(in.rightStickX);
            double a  = in.accel;
            double b  = in.brake;

            double targetSteer = lx * 0.6;
            steeringAngle += (targetSteer - steeringAngle) * STEER_RATE * dt;

            boolean drifting = Math.abs(rx) > DEADZONE;
            if (drifting && a < 0.1) {
                driftCharge = Math.min(1.0, driftCharge + 1.8 * dt);
            } else if (!drifting) {
                driftCharge *= Math.pow(0.55, dt * 2.0);
            }
            double gasLock = 1.0 - a * 0.65;
            double chargeBoost = 1.0 + driftCharge * 1.5;
            heading += rx * DRIFT_RATE * gasLock * chargeBoost * dt;
            wasOnGas = a > 0.5;

            double hx = Math.sin(heading);
            double hz = Math.cos(heading);

            if (a > 0.05) {
                vx += hx * ACCEL * a * dt;
                vz += hz * ACCEL * a * dt;
            }
            double s = Math.hypot(vx, vz);
            if (b > 0.05 && s > 1) {
                double dec = BRAKE * b * dt;
                double f = Math.max(0, (s - dec) / s);
                vx *= f; vz *= f;
            }

            double fwd = vx * hx + vz * hz;
            double latX = vx - hx * fwd;
            double latZ = vz - hz * fwd;
            slipAmount = Math.hypot(latX, latZ);

            double grip = drifting ? GRIP_DRIFTING : GRIP_NORMAL;
            double gripFactor = Math.exp(-grip * dt);
            latX *= gripFactor;
            latZ *= gripFactor;

            double speedFactor = Math.min(1.0, Math.abs(fwd) / 30.0);
            double rot = steeringAngle * STEER_INFLUENCE * dt * speedFactor;
            double cosR = Math.cos(rot), sinR = Math.sin(rot);
            double fwdX = hx * fwd, fwdZ = hz * fwd;
            double rotFwdX =  fwdX * cosR + fwdZ * sinR;
            double rotFwdZ = -fwdX * sinR + fwdZ * cosR;

            vx = rotFwdX + latX;
            vz = rotFwdZ + latZ;

            s = Math.hypot(vx, vz);
            if (s > 0.1 && a < 0.05 && b < 0.05) {
                double dec = NATURAL_DECEL * dt;
                double f = Math.max(0, (s - dec) / s);
                vx *= f; vz *= f;
            }

            s = Math.hypot(vx, vz);
            if (s > MAX_SPEED) {
                vx = vx / s * MAX_SPEED;
                vz = vz / s * MAX_SPEED;
                s = MAX_SPEED;
            }
            speed = s;

            position[0] += vx * dt;
            position[2] += vz * dt;

            applyTrackForces(track, dt);

            double limit = ROAD_WIDTH * 1.5;
            if (position[0] >  limit) { position[0] =  limit; vx *= 0.6; }
            if (position[0] < -limit) { position[0] = -limit; vx *= 0.6; }

            double total = track.totalLength();
            if (position[2] >= total) { position[2] -= total; lap++; }
            if (position[2] <  0)     position[2] += total;
            totalDistance = (lap - 1) * total + position[2];

            if (slipAmount > 18 || driftCharge > 0.25) {
                int n = (int)(slipAmount * 0.08 + driftCharge * 5);
                for (int i = 0; i < n; i++) {
                    Particle p = new Particle();
                    p.x = position[0] + (Math.random() - 0.5) * 28;
                    p.y = position[1];
                    p.z = position[2] - 12 + Math.random() * 8;
                    p.vdx = (Math.random() - 0.5) * 12;
                    p.vdy = -12 - Math.random() * 16;
                    p.vz  = -22 + (Math.random() - 0.5) * 22;
                    p.life = 0.55 + Math.random() * 0.5;
                    p.size = 6 + Math.random() * 6;
                    p.growth = 30;
                    if (Math.random() < 0.20) {
                        p.colorR = 255; p.colorG = 180; p.colorB = 60;
                        p.size = 3; p.growth = 7;
                    } else {
                        int v = 220 + (int)(Math.random() * 35);
                        p.colorR = v; p.colorG = v; p.colorB = v;
                    }
                    particles.add(p);
                }
            }
        }

        void applyTrackForces(Track track, double dt) {
            int idx = track.segmentIndex(position[2]);
            TrackSegment s = track.segments.get(idx);
            position[1] = s.y;
            double speedFrac = Math.hypot(vx, vz) / MAX_SPEED;
            position[0] -= s.curve * speedFrac * 44.0 * dt;
        }

        void updateAI(Track track, Car player, double dt) {
            int idx = track.segmentIndex(position[2]);
            TrackSegment s = track.segments.get(idx);

            double total = track.totalLength();
            double dz = player.position[2] - position[2];
            if (dz >  total * 0.5) dz -= total;
            if (dz < -total * 0.5) dz += total;

            double targetSpeed = MAX_SPEED * 0.82;
            if (dz >  120) targetSpeed = MAX_SPEED * 0.96;
            if (dz < -120) targetSpeed = MAX_SPEED * 0.72;

            TrackSegment ahead = track.segments.get(
                (idx + 10) % track.segments.size());
            double driftOffset = -ahead.curve * 0.7;
            heading += (driftOffset - heading) * 3.0 * dt;

            double hx = Math.sin(heading);
            double hz = Math.cos(heading);

            speed = Math.hypot(vx, vz);
            double newSpeed = speed + (targetSpeed - speed) * 1.3 * dt;
            vx = hx * newSpeed;
            vz = hz * newSpeed;

            double laneSeed = (color.getRGB() & 0xFFFF) * 0.001;
            double targetLane = Math.sin(position[2] * 0.004 + laneSeed) * 38;
            position[0] += (targetLane - position[0]) * 0.9 * dt;

            position[0] -= s.curve * 28.0 * dt;
            if (position[0] >  ROAD_WIDTH) position[0] =  ROAD_WIDTH;
            if (position[0] < -ROAD_WIDTH) position[0] = -ROAD_WIDTH;

            position[2] += vz * dt;
            position[1] = s.y;

            if (position[2] >= total) { position[2] -= total; lap++; }
            if (position[2] <  0)     position[2] += total;
            totalDistance = (lap - 1) * total + position[2];
        }

        static double deadzone(double v) {
            if (Math.abs(v) < DEADZONE) return 0;
            return v;
        }
    }

    // ====================== PARTICLE ======================
    static class Particle {
        double x, y, z;
        double dx, dy;
        double vdx, vdy, vz;
        double life;
        double size;
        double growth;
        int colorR, colorG, colorB;
    }

    // ====================== TRACK ======================
    static class Track {
        final List<TrackSegment> segments = new ArrayList<>();
        final List<RoadObject>   objects  = new ArrayList<>();

        void build() {
            addStraight(40);
            addCurve(60,  0.7);
            addStraight(20);
            addHill(60, 1300);
            addCurve(80, -2.0);
            addStraight(30);
            addCurve(70,  1.1);
            addHill(40, -800);
            addStraight(20);
            addCurve(90, -0.8);
            addStraight(20);
            addCurve(50,  2.4);
            addHill(40,  600);
            addStraight(50);
            addCurve(70, -1.2);
            addStraight(40);

            Random rng = new Random(1337);
            for (int i = 6; i < segments.size(); i += 2) {
                int count = rng.nextInt(2) + 1;
                for (int k = 0; k < count; k++) {
                    double side  = rng.nextBoolean() ? 1 : -1;
                    double xoff  = side * (ROAD_WIDTH * 0.94 + rng.nextDouble() * 140 + 16);
                    double z     = i * SEGMENT_LENGTH + rng.nextDouble() * SEGMENT_LENGTH;
                    int type;
                    Color c;
                    int roll = rng.nextInt(10);
                    if (roll < 3) {
                        type = 0;
                        Color[] bushColors = {
                            new Color(120, 240, 130), new Color( 80, 220, 200),
                            new Color(255, 130, 200), new Color(180, 100, 240) };
                        c = bushColors[rng.nextInt(bushColors.length)];
                    } else if (roll < 6) {
                        type = 1;
                        Color[] pineColors = {
                            new Color(80, 200, 120), new Color(60, 180, 200) };
                        c = pineColors[rng.nextInt(pineColors.length)];
                    } else if (roll < 8) {
                        type = 2;
                        c = new Color(255, 230, 160);
                    } else {
                        type = 3;
                        c = new Color(255, 200, 60);
                    }
                    objects.add(new RoadObject(xoff, z, type, c));
                }
            }
        }

        void addStraight(int len)              { addRun(len, 0, 0); }
        void addCurve(int len, double curve)   { addRun(len, curve, 0); }
        void addHill(int len, double height)   { addRun(len, 0, height); }

        void addRun(int len, double curve, double height) {
            double y0 = segments.isEmpty() ? 0 : segments.get(segments.size() - 1).y;
            for (int i = 0; i < len; i++) {
                TrackSegment s = new TrackSegment();
                double t = (double) i / Math.max(1, len);
                double ease = Math.sin(t * Math.PI);
                s.curve = curve * ease;
                s.y = y0 + height * 0.5 * (1 - Math.cos(t * Math.PI));
                segments.add(s);
            }
        }

        double totalLength() { return segments.size() * SEGMENT_LENGTH; }

        int segmentIndex(double z) {
            int n = segments.size();
            int i = ((int)(z / SEGMENT_LENGTH)) % n;
            if (i < 0) i += n;
            return i;
        }
    }

    static class TrackSegment {
        double curve, y;
        double camX, camY, camZ;
        double screenX, screenY, screenW;
        double scale;
        boolean clipped;
        double fog;
    }

    static class RoadObject {
        double xOffset, z;
        int type;
        Color color;
        RoadObject(double x, double z, int type, Color c) {
            this.xOffset = x; this.z = z; this.type = type; this.color = c;
        }
    }

    static class Sprite {
        double sx, sy, scale, fog;
        RoadObject obj;
        Car car;
        Particle particle;
        Sprite(double sx, double sy, double scale, double fog, RoadObject obj, Car car) {
            this.sx = sx; this.sy = sy; this.scale = scale; this.fog = fog; this.obj = obj; this.car = car;
        }
        Sprite(double sx, double sy, double scale, double fog, Particle p) {
            this.sx = sx; this.sy = sy; this.scale = scale; this.fog = fog; this.particle = p;
        }
    }

    // ====================== CITY SKYLINE ======================
    static class CitySkyline {
        BufferedImage image;
        int tileWidth, tileHeight;
        double parallax;
        int baseY;

        CitySkyline(double parallax, int baseY, Color bldgColor, Color windowColor,
                    int minH, int maxH, long seed, int tileWidth) {
            this.parallax = parallax;
            this.baseY = baseY;
            this.tileWidth = tileWidth;
            this.tileHeight = maxH + 40;
            image = new BufferedImage(tileWidth, tileHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            Random r = new Random(seed);

            int x = 0;
            while (x < tileWidth) {
                int bw = 36 + r.nextInt(90);
                int bh = minH + r.nextInt(Math.max(1, maxH - minH));
                if (x + bw > tileWidth) bw = tileWidth - x;

                // Building body gradient (top brighter, bottom darker)
                GradientPaint bg = new GradientPaint(
                    0, tileHeight - bh, brighter(bldgColor, 1.3),
                    0, tileHeight,      darker(bldgColor, 0.6));
                g.setPaint(bg);
                g.fillRect(x, tileHeight - bh, bw, bh);

                // Bright top edge highlight
                g.setColor(brighter(bldgColor, 1.7));
                g.fillRect(x, tileHeight - bh, bw, 2);

                // Vertical center line for definition
                g.setColor(darker(bldgColor, 0.5));
                g.fillRect(x + bw - 1, tileHeight - bh, 1, bh);

                // Antenna or rooftop element
                if (r.nextInt(4) == 0 && bw > 22) {
                    int antH = 14 + r.nextInt(40);
                    g.setColor(bldgColor);
                    g.fillRect(x + bw / 2, tileHeight - bh - antH, 2, antH);
                    g.setColor(new Color(255, 80, 80));
                    g.fillRect(x + bw / 2 - 1, tileHeight - bh - antH, 3, 3);
                }
                // Water tank / rooftop unit
                if (r.nextInt(6) == 0 && bw > 28) {
                    int wW = 8 + r.nextInt(14);
                    int wH = 6 + r.nextInt(10);
                    int wX = x + bw / 4 + r.nextInt(bw / 2);
                    g.setColor(darker(bldgColor, 0.7));
                    g.fillRect(wX, tileHeight - bh - wH, wW, wH);
                }

                // Lit windows
                if (windowColor != null) {
                    int xs = 6, ys = 7;
                    for (int wy = tileHeight - bh + 8; wy < tileHeight - 6; wy += ys) {
                        for (int wx = x + 5; wx < x + bw - 5; wx += xs) {
                            int roll = r.nextInt(5);
                            Color winC = null;
                            if (roll == 0) winC = windowColor;
                            else if (roll == 1) winC = new Color(120, 200, 255);
                            else if (roll == 2) winC = new Color(255, 150, 90);
                            if (winC != null) {
                                g.setColor(winC);
                                g.fillRect(wx, wy, 3, 4);
                            }
                        }
                    }
                }
                x += bw + 3;
            }
            g.dispose();
        }

        void draw(Graphics2D g, double playerZ) {
            double offset = (playerZ * parallax) % tileWidth;
            if (offset < 0) offset += tileWidth;
            int dx = -(int)offset;
            while (dx < WIDTH) {
                g.drawImage(image, dx, baseY - tileHeight, null);
                dx += tileWidth;
            }
        }
    }

    // ====================== RENDERER ======================
    static class Renderer {
        BufferedImage backgroundLayer;
        BufferedImage mountainsLayer;
        BufferedImage cockpitLayer;
        CitySkyline cityFar;
        CitySkyline cityNear;

        static final Color FOG_COLOR = new Color(45, 22, 78);

        Renderer() {
            buildBackground();
            buildMountains();
            buildCockpit();
            cityFar = new CitySkyline(
                0.018, HEIGHT / 2 - 30,
                new Color(28, 14, 65), null,
                40, 170, 12345L, 1400);
            cityNear = new CitySkyline(
                0.045, HEIGHT / 2 + 14,
                new Color(40, 20, 88), new Color(255, 215, 130),
                90, 250, 67890L, 1800);
        }

        void buildBackground() {
            backgroundLayer = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = backgroundLayer.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Multi-stop sky gradient (deep blue → magenta → orange near horizon)
            for (int y = 0; y < HEIGHT / 2 + 4; y++) {
                float t = (float) y / (HEIGHT / 2.0f);
                Color c;
                if (t < 0.55) {
                    float tt = t / 0.55f;
                    c = lerpColor(new Color(8, 4, 38), new Color(95, 30, 110), tt);
                } else if (t < 0.85) {
                    float tt = (t - 0.55f) / 0.30f;
                    c = lerpColor(new Color(95, 30, 110), new Color(220, 70, 130), tt);
                } else {
                    float tt = (t - 0.85f) / 0.15f;
                    c = lerpColor(new Color(220, 70, 130), new Color(255, 140, 90), tt);
                }
                g.setColor(c);
                g.fillRect(0, y, WIDTH, 1);
            }

            // Ground (under-horizon)
            GradientPaint ground = new GradientPaint(
                0, HEIGHT * 0.5f, new Color(55, 18, 78),
                0, HEIGHT,        new Color( 6,  3, 22));
            g.setPaint(ground);
            g.fillRect(0, HEIGHT / 2, WIDTH, HEIGHT / 2);

            // Stars + occasional bigger ones
            Random r = new Random(424242);
            for (int i = 0; i < 380; i++) {
                int x = r.nextInt(WIDTH);
                int y = r.nextInt((int)(HEIGHT * 0.48));
                int b = 110 + r.nextInt(140);
                boolean big = r.nextInt(12) == 0;
                if (big) {
                    g.setColor(new Color(255, 245, 220, b));
                    g.fillRect(x - 1, y, 3, 1);
                    g.fillRect(x, y - 1, 1, 3);
                } else {
                    g.setColor(new Color(255, 255, 235, b));
                    g.fillRect(x, y, 1, 1);
                }
            }

            // Distant shooting star
            g.setColor(new Color(255, 255, 255, 180));
            g.fillRect(300, 80, 60, 1);
            g.fillRect(360, 79, 8, 1);

            // Big moon with glow
            int mcx = WIDTH - 320;
            int mcy = 170;
            int mr  = 86;
            for (int i = 10; i >= 0; i--) {
                int rr = mr + i * 20;
                int a  = 36 - i * 4;
                g.setColor(new Color(255, 240, 210, Math.max(0, a)));
                g.fillOval(mcx - rr, mcy - rr, rr * 2, rr * 2);
            }
            // Moon disc with subtle gradient
            GradientPaint moonG = new GradientPaint(
                mcx - mr, mcy - mr, new Color(255, 250, 235),
                mcx + mr, mcy + mr, new Color(220, 200, 180));
            g.setPaint(moonG);
            g.fillOval(mcx - mr, mcy - mr, mr * 2, mr * 2);
            // Craters
            g.setColor(new Color(170, 155, 140, 140));
            g.fillOval(mcx - mr + 18, mcy - mr + 24, 28, 22);
            g.fillOval(mcx + 12,      mcy - 8,        24, 18);
            g.fillOval(mcx - 8,       mcy + 28,       18, 14);
            g.fillOval(mcx + 28,      mcy + 22,       12, 10);

            // Aurora / nebula wisps
            g.setColor(new Color(120, 180, 255, 30));
            for (int i = 0; i < 5; i++) {
                int wy = 120 + i * 35;
                int ww = WIDTH;
                for (int wx = 0; wx < ww; wx += 8) {
                    int h = (int)(8 + 6 * Math.sin(wx * 0.01 + i * 1.7));
                    g.fillRect(wx, wy, 8, h);
                }
            }

            // Horizon sun bleed
            int yTop = (int)(HEIGHT * 0.43f);
            int yBot = HEIGHT / 2 + 8;
            GradientPaint glow = new GradientPaint(
                0, yTop, new Color(255, 110, 90, 0),
                0, yBot, new Color(255, 140, 80, 190));
            g.setPaint(glow);
            g.fillRect(0, yTop, WIDTH, yBot - yTop);

            // Vaporwave grid lines on ground (perspective)
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(new Color(255, 80, 200, 55));
            for (int i = 1; i < 16; i++) {
                double tFrac = i / 16.0;
                int y2 = (int)(HEIGHT / 2.0 + tFrac * tFrac * HEIGHT / 2.0);
                g.drawLine(0, y2, WIDTH, y2);
            }
            // Vertical grid converging to vanishing point
            for (int i = -10; i <= 10; i++) {
                int xb = WIDTH / 2 + i * 200;
                g.drawLine(WIDTH / 2, HEIGHT / 2, xb, HEIGHT);
            }

            g.dispose();
        }

        void buildMountains() {
            int mw = 2400;
            int mh = 380;
            mountainsLayer = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = mountainsLayer.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Far mountains
            g.setColor(new Color(50, 22, 100));
            Path2D mtn = new Path2D.Double();
            mtn.moveTo(-50, mh);
            for (int i = 0; i <= 32; i++) {
                double xx = i * mw / 32.0;
                double h = 90 + 140 * Math.abs(Math.sin(i * 1.7));
                mtn.lineTo(xx, mh - h);
            }
            mtn.lineTo(mw + 50, mh);
            mtn.closePath();
            g.fill(mtn);

            // Mid mountains with snow caps
            g.setColor(new Color(74, 30, 130));
            Path2D mtn2 = new Path2D.Double();
            mtn2.moveTo(-50, mh);
            double[] peaks = new double[40];
            for (int i = 0; i <= 40; i++) {
                double xx = i * mw / 40.0;
                double h = 50 + 100 * Math.abs(Math.sin(i * 2.4 + 1.3));
                peaks[i % peaks.length] = h;
                mtn2.lineTo(xx, mh - h);
            }
            mtn2.lineTo(mw + 50, mh);
            mtn2.closePath();
            g.fill(mtn2);

            // Snow caps on peaks
            g.setColor(new Color(240, 220, 240, 220));
            for (int i = 1; i <= 39; i++) {
                double xx = i * mw / 40.0;
                double h = 50 + 100 * Math.abs(Math.sin(i * 2.4 + 1.3));
                double hL = 50 + 100 * Math.abs(Math.sin((i-1) * 2.4 + 1.3));
                double hR = 50 + 100 * Math.abs(Math.sin((i+1) * 2.4 + 1.3));
                if (h > hL && h > hR && h > 100) {
                    double tipY = mh - h;
                    Path2D snow = new Path2D.Double();
                    snow.moveTo(xx, tipY);
                    snow.lineTo(xx + 14, tipY + 16);
                    snow.lineTo(xx - 14, tipY + 16);
                    snow.closePath();
                    g.fill(snow);
                }
            }
            g.dispose();
        }

        void buildCockpit() {
            cockpitLayer = new BufferedImage(WIDTH, 260, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = cockpitLayer.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Hood curve (front of car visible at bottom)
            Path2D hood = new Path2D.Double();
            hood.moveTo(0, 260);
            hood.lineTo(0, 200);
            hood.curveTo(WIDTH * 0.20, 50, WIDTH * 0.80, 50, WIDTH, 200);
            hood.lineTo(WIDTH, 260);
            hood.closePath();

            // Hood gradient
            GradientPaint hg = new GradientPaint(
                0, 60,  new Color(120, 30, 40),
                0, 260, new Color(35, 12, 22));
            g.setPaint(hg);
            g.fill(hood);

            // Hood split line (center crease)
            g.setColor(new Color(20, 8, 14));
            g.setStroke(new BasicStroke(2f));
            Path2D crease = new Path2D.Double();
            crease.moveTo(WIDTH / 2.0, 260);
            crease.curveTo(WIDTH / 2.0 + 4, 130, WIDTH / 2.0 + 8, 80, WIDTH / 2.0, 60);
            g.draw(crease);

            // Hood highlight
            g.setColor(new Color(255, 130, 140, 110));
            g.setStroke(new BasicStroke(3f));
            Path2D hl = new Path2D.Double();
            hl.moveTo(WIDTH * 0.20, 200);
            hl.curveTo(WIDTH * 0.30, 110, WIDTH * 0.46, 80, WIDTH * 0.50, 70);
            g.draw(hl);

            // Hood ornaments / vents
            g.setColor(new Color(20, 10, 18));
            g.fillRect(WIDTH / 2 - 60, 90, 30, 8);
            g.fillRect(WIDTH / 2 + 30, 90, 30, 8);

            // A-pillars (sides)
            g.setColor(new Color(15, 8, 18));
            int pillarBaseW = 60;
            int[] lpX = { 0, pillarBaseW, 0 };
            int[] lpY = { 0, 0, 260 };
            g.fillPolygon(lpX, lpY, 3);
            int[] rpX = { WIDTH, WIDTH - pillarBaseW, WIDTH };
            int[] rpY = { 0, 0, 260 };
            g.fillPolygon(rpX, rpY, 3);

            g.dispose();
        }

        void render(Graphics2D g, Car player, Track track, List<Car> aiCars,
                    List<Particle> particles, int fps, Input input) {
            g.drawImage(backgroundLayer, 0, 0, null);
            drawMountains(g, player.position[2]);
            cityFar.draw(g, player.position[2]);
            cityNear.draw(g, player.position[2]);
            drawWorld(g, player, track, aiCars, particles);
            drawSpeedLines(g, player);
            g.drawImage(cockpitLayer, 0, HEIGHT - 260, null);
            drawPlayerCar(g, player);
            drawHUD(g, player, aiCars, fps, input);
        }

        void drawMountains(Graphics2D g, double playerZ) {
            int mw = mountainsLayer.getWidth();
            int mh = mountainsLayer.getHeight();
            double offset = (playerZ * 0.025) % mw;
            if (offset < 0) offset += mw;
            int dx = -(int)offset;
            int yPos = HEIGHT / 2 - mh + 65;
            while (dx < WIDTH) {
                g.drawImage(mountainsLayer, dx, yPos, null);
                dx += mw;
            }
        }

        void drawSpeedLines(Graphics2D g, Car player) {
            double f = player.speed / MAX_SPEED;
            if (f < 0.5) return;
            double intensity = Math.min(1.0, (f - 0.5) * 2.0);
            int alpha = (int)(intensity * 90);
            int count = (int)(intensity * 30);

            g.setColor(new Color(255, 255, 255, alpha));
            // Deterministic per-frame using nanoTime for slight motion
            Random r = new Random(System.nanoTime() / 50_000_000L);
            for (int i = 0; i < count; i++) {
                double ang = r.nextDouble() * Math.PI * 2;
                double dist = 280 + r.nextDouble() * 600;
                int sx = (int)(CENTER_X + Math.cos(ang) * dist);
                int sy = (int)(HEIGHT * 0.55 + Math.sin(ang) * dist * 0.5);
                int len = 20 + r.nextInt(60);
                int dx = (int)(Math.cos(ang) * len);
                int dy = (int)(Math.sin(ang) * len * 0.5);
                g.drawLine(sx, sy, sx + dx, sy + dy);
            }
        }

        void drawWorld(Graphics2D g, Car player, Track track, List<Car> aiCars,
                       List<Particle> particles) {
            int total = track.segments.size();
            int baseSeg = track.segmentIndex(player.position[2]);

            double camX = player.position[0];
            double camY = player.position[1] + CAM_HEIGHT;
            double camZ = player.position[2] - CAM_BEHIND;

            TrackSegment[] proj = new TrackSegment[DRAW_DISTANCE];
            double x = 0, dx = 0;
            for (int n = 0; n < DRAW_DISTANCE; n++) {
                int idx = (baseSeg + n) % total;
                if (idx < 0) idx += total;
                TrackSegment s = track.segments.get(idx);

                double segWorldZ = (baseSeg + n) * SEGMENT_LENGTH;
                double relZ = segWorldZ - camZ;
                if (relZ < 1) relZ = 1;
                double relX = x - camX;
                double relY = s.y - camY;

                s.scale   = FOCAL / relZ;
                s.screenX = CENTER_X + s.scale * relX;
                s.screenY = CENTER_Y - s.scale * relY;
                s.screenW = s.scale * ROAD_WIDTH;
                s.camX = relX; s.camY = relY; s.camZ = relZ;
                s.clipped = relZ <= 1;
                s.fog = fogFactor(n);

                proj[n] = s;
                dx += s.curve;
                x  += dx;
            }

            double minY = HEIGHT;
            for (int n = 1; n < DRAW_DISTANCE; n++) {
                TrackSegment near = proj[n - 1];
                TrackSegment far  = proj[n];
                if (near.clipped || far.clipped) continue;
                if (far.screenY >= minY) continue;

                int gIdx = baseSeg + n;
                boolean dark = (gIdx / 3) % 2 == 0;
                double fog = far.fog;

                Color shoulder = applyFog(dark
                    ? new Color(38, 16, 64)
                    : new Color(52, 22, 84), fog);
                drawQuad(g, shoulder,
                    0, (int)near.screenY, WIDTH,
                    0, (int)far.screenY,  WIDTH);

                Color curb = applyFog(((gIdx / 3) % 2 == 0)
                    ? new Color(230, 40, 60)
                    : new Color(245, 245, 250), fog);
                int cW1 = (int)(near.screenW * 1.24);
                int cW2 = (int)(far .screenW * 1.24);
                drawQuad(g, curb,
                    (int)(near.screenX - cW1), (int)near.screenY, cW1 * 2,
                    (int)(far .screenX - cW2), (int)far .screenY, cW2 * 2);

                Color road = applyFog(dark
                    ? new Color(34, 26, 50)
                    : new Color(54, 42, 74), fog);
                drawQuad(g, road,
                    (int)(near.screenX - near.screenW), (int)near.screenY, (int)(near.screenW * 2),
                    (int)(far .screenX - far .screenW), (int)far .screenY, (int)(far .screenW  * 2));

                if (((gIdx / 3) % 2) == 0) {
                    int lW1 = Math.max(1, (int)(near.screenW * 0.045));
                    int lW2 = Math.max(1, (int)(far .screenW * 0.045));
                    drawQuad(g, applyFog(new Color(255, 220, 80), fog),
                        (int)(near.screenX - lW1), (int)near.screenY, lW1 * 2,
                        (int)(far .screenX - lW2), (int)far .screenY, lW2 * 2);
                }

                int eW1 = Math.max(1, (int)(near.screenW * 0.035));
                int eW2 = Math.max(1, (int)(far .screenW * 0.035));
                Color edge = applyFog(new Color(220, 220, 230), fog);
                drawQuad(g, edge,
                    (int)(near.screenX - near.screenW), (int)near.screenY, eW1,
                    (int)(far .screenX - far .screenW), (int)far .screenY, eW2);
                drawQuad(g, edge,
                    (int)(near.screenX + near.screenW - eW1), (int)near.screenY, eW1,
                    (int)(far .screenX + far .screenW - eW2), (int)far .screenY, eW2);

                minY = far.screenY;
            }

            // Collect sprites
            List<Sprite> sprites = new ArrayList<>(
                track.objects.size() + aiCars.size() + particles.size());
            for (RoadObject o : track.objects) {
                int segIdx = (int)(o.z / SEGMENT_LENGTH);
                int rel = segIdx - baseSeg;
                while (rel < 0) rel += total;
                if (rel >= DRAW_DISTANCE - 1) continue;
                TrackSegment s = proj[rel];
                if (s.clipped) continue;
                double sx = s.screenX + s.scale * o.xOffset;
                sprites.add(new Sprite(sx, s.screenY, s.scale, s.fog, o, null));
            }
            for (Car ai : aiCars) {
                int segIdx = track.segmentIndex(ai.position[2]);
                int rel = segIdx - baseSeg;
                while (rel < 0) rel += total;
                if (rel >= DRAW_DISTANCE - 1) continue;
                TrackSegment s = proj[rel];
                if (s.clipped) continue;
                double sx = s.screenX + s.scale * ai.position[0];
                sprites.add(new Sprite(sx, s.screenY, s.scale, s.fog, null, ai));
            }
            for (Particle p : particles) {
                int segIdx = (int)(p.z / SEGMENT_LENGTH);
                int rel = segIdx - baseSeg;
                while (rel < 0) rel += total;
                if (rel >= DRAW_DISTANCE - 1) continue;
                TrackSegment s = proj[rel];
                if (s.clipped) continue;
                double sx = s.screenX + s.scale * p.x + p.dx;
                double sy = s.screenY + p.dy;
                sprites.add(new Sprite(sx, sy, s.scale, s.fog, p));
            }
            sprites.sort((a, b) -> Double.compare(a.scale, b.scale));
            for (Sprite sp : sprites) {
                if (sp.obj      != null) drawTrackObject(g, sp);
                else if (sp.car != null) drawAICar(g, sp);
                else                     drawParticle(g, sp);
            }
        }

        double fogFactor(int n) {
            if (n < FOG_START) return 0;
            if (n > FOG_END)   return 1;
            return (n - FOG_START) / (FOG_END - FOG_START);
        }

        Color applyFog(Color c, double f) {
            if (f <= 0) return c;
            if (f >= 1) return FOG_COLOR;
            return lerpColor(c, FOG_COLOR, (float) f);
        }

        void drawQuad(Graphics2D g, Color c,
                      int x1, int y1, int w1,
                      int x2, int y2, int w2) {
            int[] xs = { x1, x1 + w1, x2 + w2, x2 };
            int[] ys = { y1, y1,      y2,      y2 };
            g.setColor(c);
            g.fillPolygon(xs, ys, 4);
        }

        void drawTrackObject(Graphics2D g, Sprite sp) {
            double size = sp.scale * 42;
            if (size < 0.8) return;
            RoadObject o = sp.obj;
            Color objColor = applyFog(o.color, sp.fog);
            double cx = sp.sx, by = sp.sy;

            switch (o.type) {
                case 0: {
                    int w = (int)(size * 1.8), h = (int)(size * 1.4);
                    g.setColor(applyFog(darker(o.color, 0.4), sp.fog));
                    g.fillOval((int)(cx - w / 2.0), (int)(by - h * 0.85), w, h);
                    g.setColor(applyFog(darker(o.color, 0.7), sp.fog));
                    g.fillOval((int)(cx - w / 2.6), (int)(by - h * 1.15),
                               (int)(w * 0.78),    (int)(h * 0.9));
                    g.setColor(objColor);
                    g.fillOval((int)(cx - w / 4.0), (int)(by - h * 1.4),
                               (int)(w * 0.55),    (int)(h * 0.7));
                    g.setColor(applyFog(brighter(o.color, 1.3), sp.fog));
                    g.fillOval((int)(cx - w / 8.0), (int)(by - h * 1.5),
                               (int)(w * 0.25),    (int)(h * 0.35));
                    break;
                }
                case 1: {
                    int tw = Math.max(2, (int)(size * 0.18));
                    int th = (int)(size * 1.6);
                    g.setColor(applyFog(new Color(50, 30, 22), sp.fog));
                    g.fillRect((int)(cx - tw / 2.0), (int)(by - th), tw, th);
                    for (int i = 2; i >= 0; i--) {
                        double yoff = th + i * size * 0.55;
                        double w    = size * (1.35 - i * 0.22);
                        Color tc = (i == 0) ? brighter(o.color, 1.3)
                                  : (i == 1 ? o.color : darker(o.color, 0.6));
                        g.setColor(applyFog(tc, sp.fog));
                        int[] xs = { (int)(cx - w), (int)(cx + w), (int)cx };
                        int[] ys = { (int)(by - yoff), (int)(by - yoff),
                                     (int)(by - yoff - size * 1.25) };
                        g.fillPolygon(xs, ys, 3);
                    }
                    break;
                }
                case 2: {
                    int pw = Math.max(2, (int)(size * 0.14));
                    int ph = (int)(size * 3.6);
                    g.setColor(applyFog(new Color(75, 60, 100), sp.fog));
                    g.fillRect((int)(cx - pw / 2.0), (int)(by - ph), pw, ph);
                    g.fillRect((int)(cx - size * 0.5), (int)(by - ph),
                               (int)(size * 1.0), Math.max(2, pw));
                    // 6-pointed lens flare
                    int gr = (int)(size * 1.2);
                    for (int i = 8; i > 0; i--) {
                        int rr = gr + i * 7;
                        int a  = (int) ((28 - i * 3) * (1 - sp.fog));
                        if (a <= 0) continue;
                        g.setColor(new Color(255, 220, 130, a));
                        g.fillOval((int)(cx - rr), (int)(by - ph - rr / 3),
                                   rr * 2, (int)(rr * 1.1));
                    }
                    g.setColor(applyFog(new Color(255, 240, 175), sp.fog));
                    int br = (int)(size * 0.42);
                    g.fillOval((int)(cx - br), (int)(by - ph - br / 3),
                               br * 2, (int)(br * 1.1));
                    // Streak
                    int a = (int) (180 * (1 - sp.fog));
                    if (a > 0) {
                        g.setColor(new Color(255, 240, 200, a));
                        g.fillRect((int)(cx - size * 2.5), (int)(by - ph), (int)(size * 5), 1);
                    }
                    break;
                }
                default: {
                    int pw = Math.max(2, (int)(size * 0.14));
                    int ph = (int)(size * 2.4);
                    g.setColor(applyFog(new Color(60, 50, 80), sp.fog));
                    g.fillRect((int)(cx - pw / 2.0), (int)(by - ph), pw, ph);
                    int sw = (int)(size * 2.1), sh = (int)(size * 1.2);
                    g.setColor(objColor);
                    g.fillRect((int)(cx - sw / 2.0), (int)(by - ph - sh * 0.2), sw, sh);
                    g.setColor(applyFog(darker(o.color, 0.45), sp.fog));
                    g.drawRect((int)(cx - sw / 2.0), (int)(by - ph - sh * 0.2), sw, sh);
                    g.setColor(applyFog(new Color(20, 10, 30), sp.fog));
                    int ax = (int)cx;
                    int ay = (int)(by - ph + sh * 0.3);
                    int aw = Math.max(3, (int)(size * 0.44));
                    int[] axs = { ax - aw, ax - aw, ax + aw };
                    int[] ays = { ay - aw, ay + aw, ay };
                    g.fillPolygon(axs, ays, 3);
                    break;
                }
            }
        }

        void drawAICar(Graphics2D g, Sprite sp) {
            Car ai = sp.car;
            double size = sp.scale * 32;
            if (size < 0.8) return;
            double cx = sp.sx, by = sp.sy;

            int w  = (int)(size * 2.4);
            int h  = (int)(size * 1.55);
            int x  = (int)(cx - w / 2.0);
            int y  = (int)(by - h);

            Color body = applyFog(ai.color, sp.fog);

            g.setColor(new Color(0, 0, 0, (int)(150 * (1 - sp.fog))));
            g.fillOval(x - 6, (int)(by - 7), w + 12, 16);

            // Wheels (visible from rear)
            int wheelD = Math.max(4, (int)(size * 0.55));
            g.setColor(applyFog(new Color(15, 8, 18), sp.fog));
            g.fillOval(x - wheelD / 4, y + h - wheelD / 2 - 2, wheelD, wheelD);
            g.fillOval(x + w - wheelD + wheelD / 4, y + h - wheelD / 2 - 2, wheelD, wheelD);

            // Bumper
            g.setColor(applyFog(darker(ai.color, 0.5), sp.fog));
            int[] bX = { x - 4, x + w + 4, x + w - 1, x + 1 };
            int[] bY = { y + h, y + h, y + h - 6, y + h - 6 };
            g.fillPolygon(bX, bY, 4);

            // Body main (solid, fogged)
            g.setColor(body);
            int[] mX = { x, x + w, x + (int)(w * 0.78), x + (int)(w * 0.22) };
            int[] mY = { y + h, y + h, y, y };
            g.fillPolygon(mX, mY, 4);
            // Body highlight strip (top edge brighter)
            g.setColor(applyFog(brighter(ai.color, 1.25), sp.fog));
            int[] hlX = { x + (int)(w * 0.22), x + (int)(w * 0.78), x + (int)(w * 0.72), x + (int)(w * 0.28) };
            int[] hlY = { y, y, y + 3, y + 3 };
            g.fillPolygon(hlX, hlY, 4);

            // Left shading triangle
            g.setColor(applyFog(darker(ai.color, 0.5), sp.fog));
            int[] sX = { x, x + (int)(w * 0.22), x + (int)(w * 0.32) };
            int[] sY = { y + h, y, y };
            g.fillPolygon(sX, sY, 3);

            // Side skirt
            g.setColor(applyFog(darker(ai.color, 0.25), sp.fog));
            int[] skX = { x, x + w, x + (int)(w * 0.78), x + (int)(w * 0.22) };
            int[] skY = { y + h, y + h, (int)(y + h * 0.65), (int)(y + h * 0.65) };
            g.fillPolygon(skX, skY, 4);

            // Roof
            g.setColor(applyFog(darker(ai.color, 0.4), sp.fog));
            int[] rX = { x + (int)(w * 0.30), x + (int)(w * 0.70),
                         x + (int)(w * 0.60), x + (int)(w * 0.40) };
            int[] rY = { y + (int)(h * 0.50), y + (int)(h * 0.50),
                         y + (int)(h * 0.10), y + (int)(h * 0.10) };
            g.fillPolygon(rX, rY, 4);

            // Rear window
            g.setColor(applyFog(new Color(40, 50, 90), sp.fog));
            int[] wX = { x + (int)(w * 0.33), x + (int)(w * 0.67),
                         x + (int)(w * 0.58), x + (int)(w * 0.42) };
            int[] wY = { y + (int)(h * 0.48), y + (int)(h * 0.48),
                         y + (int)(h * 0.18), y + (int)(h * 0.18) };
            g.fillPolygon(wX, wY, 4);

            // Rear spoiler
            if (w > 18) {
                g.setColor(applyFog(new Color(20, 10, 30), sp.fog));
                g.fillRect(x + (int)(w * 0.10), y + h - 14, (int)(w * 0.80), 4);
                g.setColor(applyFog(new Color(60, 50, 80), sp.fog));
                g.fillRect(x + (int)(w * 0.14), y + h - 18, (int)(w * 0.72), 3);
            }

            // Tail lights with stronger glow
            int tw = Math.max(3, w / 5);
            int th = Math.max(2, h / 7);
            int gAlpha = (int)(180 * (1 - sp.fog));
            for (int i = 4; i > 0; i--) {
                g.setColor(new Color(255, 60, 60, Math.max(0, gAlpha / 5 - i * 4)));
                g.fillRoundRect(x - i, y + h - th - 5 - i, tw + i * 2, th + i, 4, 4);
                g.fillRoundRect(x + w - tw - i, y + h - th - 5 - i, tw + i * 2, th + i, 4, 4);
            }
            g.setColor(applyFog(new Color(255, 110, 95), sp.fog));
            g.fillRoundRect(x + 2,          y + h - th - 4, tw, th, 3, 3);
            g.fillRoundRect(x + w - tw - 2, y + h - th - 4, tw, th, 3, 3);
        }

        void drawParticle(Graphics2D g, Sprite sp) {
            Particle p = sp.particle;
            double r = Math.max(1.5, p.size * Math.max(0.3, sp.scale * 2.0));
            double fogMul = 1 - sp.fog * 0.7;
            int a = (int) Math.min(255, p.life * 360 * fogMul);
            g.setColor(new Color(p.colorR, p.colorG, p.colorB, Math.max(0, a)));
            g.fillOval((int)(sp.sx - r / 2), (int)(sp.sy - r / 2), (int)r, (int)r);
        }

        void drawPlayerCar(Graphics2D g, Car player) {
            double cx = CENTER_X;
            double cy = HEIGHT - 260;          // sits above the cockpit hood
            double driftAngle = player.heading * 0.7;

            AffineTransform old = g.getTransform();
            g.translate(cx, cy);
            g.rotate(driftAngle);

            int bodyW = 260;
            int bodyH = 170;
            int hw = bodyW / 2;
            int hh = bodyH / 2;

            // Soft ground shadow
            g.setColor(new Color(0, 0, 0, 170));
            g.fillOval(-hw - 24, hh - 4, bodyW + 48, 40);

            // Rear wheels (visible)
            g.setColor(new Color(8, 4, 12));
            g.fillOval(-hw - 12, hh - 12, 36, 36);
            g.fillOval( hw - 24, hh - 12, 36, 36);
            // Wheel rim spokes (small accent)
            g.setColor(new Color(120, 110, 130));
            g.fillOval(-hw + 2, hh + 2, 14, 14);
            g.fillOval( hw - 16, hh + 2, 14, 14);

            // Rear bumper
            g.setColor(darker(player.color, 0.5));
            int[] bX = { -hw - 16, hw + 16, hw, -hw };
            int[] bY = {  hh + 12,  hh + 12, hh - 12, hh - 12 };
            g.fillPolygon(bX, bY, 4);

            // Main body with vertical gradient
            GradientPaint mbg = new GradientPaint(
                0, -hh, brighter(player.color, 1.2),
                0, hh,  darker(player.color, 0.6));
            g.setPaint(mbg);
            int[] mX = { -hw, hw, (int)(hw * 0.62), (int)(-hw * 0.62) };
            int[] mY = {  hh, hh, -hh, -hh };
            g.fillPolygon(mX, mY, 4);

            // Hood shading on right side (lighting fake)
            g.setColor(darker(player.color, 0.62));
            int[] sX = { -hw, (int)(-hw * 0.62), (int)(-hw * 0.32), (int)(-hw * 0.18) };
            int[] sY = {  hh, -hh,                -hh,                 hh };
            g.fillPolygon(sX, sY, 4);

            // Side skirt
            g.setColor(darker(player.color, 0.3));
            int[] skirtX = { -hw, hw, (int)(hw * 0.62), (int)(-hw * 0.62) };
            int[] skirtY = {  hh, hh, (int)(hh * 0.58), (int)(hh * 0.58) };
            g.fillPolygon(skirtX, skirtY, 4);

            // Wheel arches (dark recesses)
            g.setColor(new Color(15, 8, 22));
            g.fillArc(-hw - 6, hh - 22, 36, 24, 0, 180);
            g.fillArc( hw - 30, hh - 22, 36, 24, 0, 180);

            // Roof with gradient
            GradientPaint rg = new GradientPaint(
                0, (int)(-hh * 0.50), brighter(player.color, 1.05),
                0, (int)(hh * 0.25),  darker(player.color, 0.55));
            g.setPaint(rg);
            int[] roofX = { (int)(-hw * 0.60), (int)(hw * 0.60),
                            (int)( hw * 0.48), (int)(-hw * 0.48) };
            int[] roofY = { (int)( hh * 0.25), (int)(hh * 0.25),
                            (int)(-hh * 0.50), (int)(-hh * 0.50) };
            g.fillPolygon(roofX, roofY, 4);

            // Rear window
            g.setColor(new Color(35, 45, 95));
            int[] winX = { (int)(-hw * 0.55), (int)(hw * 0.55),
                           (int)( hw * 0.46), (int)(-hw * 0.46) };
            int[] winY = { (int)( hh * 0.20), (int)(hh * 0.20),
                           (int)(-hh * 0.25), (int)(-hh * 0.25) };
            g.fillPolygon(winX, winY, 4);

            // Window glint
            g.setColor(new Color(200, 220, 255, 130));
            int[] gnX = { (int)(-hw * 0.55), (int)(-hw * 0.10),
                          (int)(-hw * 0.18), (int)(-hw * 0.46) };
            int[] gnY = { (int)( hh * 0.18), (int)(hh * 0.18),
                          (int)(-hh * 0.22), (int)(-hh * 0.22) };
            g.fillPolygon(gnX, gnY, 4);

            // Rear spoiler (3-piece for prominence)
            g.setColor(new Color(10, 6, 18));
            g.fillRect(-(int)(hw * 0.90), hh - 4,  (int)(bodyW * 0.90), 5);
            g.setColor(new Color(50, 40, 65));
            g.fillRect(-(int)(hw * 0.96), hh - 14, (int)(bodyW * 0.96), 4);
            g.setColor(new Color(20, 12, 30));
            g.fillRect(-(int)(hw * 0.96), hh - 18, (int)(bodyW * 0.96), 4);

            // Side mirrors
            g.setColor(darker(player.color, 0.55));
            g.fillRoundRect(-(int)(hw * 0.66), -hh + 14, 18, 12, 4, 4);
            g.fillRoundRect( (int)(hw * 0.66) - 18, -hh + 14, 18, 12, 4, 4);

            // Multi-layer tail lights
            int tlw = 42, tlh = 14;
            for (int i = 6; i > 0; i--) {
                g.setColor(new Color(255, 50, 60, 56 - i * 9));
                g.fillRoundRect(-hw - 4 - i, hh - tlh - 10 - i / 2,
                                tlw + i * 2, tlh + i, 7, 7);
                g.fillRoundRect( hw + 4 - tlw - i, hh - tlh - 10 - i / 2,
                                tlw + i * 2, tlh + i, 7, 7);
            }
            g.setColor(new Color(255, 90, 80));
            g.fillRoundRect(-hw + 6,        hh - tlh - 10, tlw, tlh, 6, 6);
            g.fillRoundRect( hw - 6 - tlw,  hh - tlh - 10, tlw, tlh, 6, 6);
            g.setColor(new Color(255, 230, 200));
            g.fillRoundRect(-hw + 12,      hh - tlh - 7, tlw - 12, 3, 2, 2);
            g.fillRoundRect( hw - tlw,     hh - tlh - 7, tlw - 12, 3, 2, 2);

            // Hood stripe (racing accent)
            g.setColor(new Color(245, 245, 250, 200));
            int[] stX = { (int)(-hw * 0.10), (int)(hw * 0.10),
                          (int)( hw * 0.08), (int)(-hw * 0.08) };
            int[] stY = {  (int)(hh * 0.55), (int)(hh * 0.55),
                          -hh,             -hh };
            g.fillPolygon(stX, stY, 4);

            // Exhaust flames
            boolean flameOn = player.driftCharge > 0.25
                || (player.speed > MAX_SPEED * 0.55 && player.wasOnGas);
            if (flameOn) {
                double flicker = 1.0 + 0.4 * Math.sin(System.nanoTime() * 1e-7);
                double size = (1.0 + player.driftCharge * 1.2) * flicker;
                int flW = (int)(16 * size), flH = (int)(28 * size);
                g.setColor(new Color(255, 130, 40, 220));
                g.fillOval(-hw + 22 - flW / 2, hh + 8, flW, flH);
                g.fillOval( hw - 22 - flW / 2, hh + 8, flW, flH);
                g.setColor(new Color(255, 230, 180, 235));
                g.fillOval(-hw + 22 - flW / 3, hh + 8, flW * 2 / 3, flH * 2 / 3);
                g.fillOval( hw - 22 - flW / 3, hh + 8, flW * 2 / 3, flH * 2 / 3);
            }

            g.setTransform(old);
        }

        void drawHUD(Graphics2D g, Car player, List<Car> aiCars, int fps, Input input) {
            int pos = 1;
            for (Car ai : aiCars) if (ai.totalDistance > player.totalDistance) pos++;
            int totalRacers = aiCars.size() + 1;

            String posStr = String.valueOf(pos);
            g.setFont(new Font("Impact", Font.BOLD, 160));
            FontMetrics fm = g.getFontMetrics();
            int psw = fm.stringWidth(posStr);
            g.setColor(new Color(0, 0, 0, 200));
            g.drawString(posStr, 64, 178);
            g.setColor(Color.WHITE);
            g.drawString(posStr, 60, 172);

            String suffix = ordinalSuffix(pos);
            g.setFont(new Font("Impact", Font.BOLD, 54));
            g.setColor(new Color(0, 0, 0, 200));
            g.drawString(suffix, 68 + psw + 10, 78);
            g.setColor(Color.WHITE);
            g.drawString(suffix, 64 + psw + 10, 72);
            g.setFont(new Font("Monospaced", Font.BOLD, 24));
            g.setColor(new Color(255, 100, 180));
            g.drawString("OF " + totalRacers, 66 + psw + 10, 120);

            g.setFont(new Font("Impact", Font.BOLD, 42));
            String lapText = "LAP " + Math.min(player.lap, 3) + "/3";
            g.setColor(new Color(0, 0, 0, 200));
            g.drawString(lapText, 348, 76);
            g.setColor(Color.WHITE);
            g.drawString(lapText, 344, 72);

            int kmh = (int)(player.speed * 0.7);
            String spStr = String.valueOf(kmh);
            g.setFont(new Font("Impact", Font.BOLD, 140));
            FontMetrics sfm = g.getFontMetrics();
            int spW = sfm.stringWidth(spStr);
            int spX = WIDTH - 210 - spW;
            g.setColor(new Color(0, 0, 0, 200));
            g.drawString(spStr, spX + 4, 174);
            g.setColor(Color.WHITE);
            g.drawString(spStr, spX, 170);
            g.setFont(new Font("Impact", Font.BOLD, 36));
            g.setColor(new Color(255, 100, 180));
            g.drawString("KM/H", WIDTH - 192, 100);

            int barX = WIDTH - 560, barY = 192, barW = 500, barH = 20;
            g.setColor(new Color(0, 0, 0, 170));
            g.fillRect(barX - 2, barY - 2, barW + 4, barH + 4);
            int barFill = (int)(barW * Math.min(1.0, player.speed / MAX_SPEED));
            GradientPaint sg = new GradientPaint(
                barX,        barY, new Color(70, 240, 255),
                barX + barW, barY, new Color(255, 60, 200));
            g.setPaint(sg);
            g.fillRect(barX, barY, barFill, barH);
            g.setColor(new Color(255, 255, 255, 130));
            for (int i = 1; i < 10; i++) {
                int tx = barX + (int)(barW * i / 10.0);
                g.fillRect(tx, barY, 1, barH);
            }

            int dx = 64, dy = HEIGHT - 360, dw = 420, dh = 22;
            g.setColor(new Color(0, 0, 0, 180));
            g.fillRoundRect(dx - 8, dy - 36, dw + 16, dh + 50, 10, 10);
            g.setFont(new Font("Impact", Font.BOLD, 28));
            g.setColor(new Color(255, 100, 180));
            g.drawString("DRIFT CHARGE", dx, dy - 8);
            g.setColor(new Color(30, 14, 50));
            g.fillRect(dx, dy, dw, dh);
            int fill = (int)(dw * player.driftCharge);
            GradientPaint cg = new GradientPaint(
                dx,      dy, new Color(80, 220, 255),
                dx + dw, dy, new Color(255, 80, 200));
            g.setPaint(cg);
            g.fillRect(dx, dy, fill, dh);
            if (player.driftCharge > 0.9) {
                g.setColor(new Color(255, 255, 255, 220));
                g.setFont(new Font("Impact", Font.BOLD, 24));
                g.drawString("MAX!", dx + dw - 86, dy - 8);
            }

            // ---- Controller status (right side, above cockpit) ----
            String padLabel;
            Color padCol;
            Controller c = input.controller;
            if (c != null && c.connected) {
                padLabel = "PAD: CONNECTED";
                padCol = new Color(80, 240, 130);
            } else if (c != null && c.spawned) {
                padLabel = "PAD: " + c.statusMsg;
                padCol = new Color(255, 200, 80);
            } else {
                padLabel = "KEYBOARD ONLY";
                padCol = new Color(220, 140, 220);
            }
            g.setFont(new Font("Impact", Font.BOLD, 24));
            g.setColor(new Color(0, 0, 0, 200));
            g.drawString(padLabel, WIDTH - 460 + 2, HEIGHT - 372 + 2);
            g.setColor(padCol);
            g.drawString(padLabel, WIDTH - 460, HEIGHT - 372);

            // Footer hint (above cockpit)
            g.setColor(new Color(230, 220, 255, 180));
            g.setFont(new Font("Monospaced", Font.PLAIN, 16));
            String hint = (input.usingController)
                ? "L-stick steer    R-stick drift    RT accel    LT brake"
                : "W/S accel/brake     A/D steer (L-stick)     J/L drift (R-stick)";
            int hw2 = g.getFontMetrics().stringWidth(hint);
            g.drawString(hint, (WIDTH - hw2) / 2, HEIGHT - 270);

            g.setColor(new Color(180, 180, 200, 160));
            g.setFont(new Font("Monospaced", Font.PLAIN, 13));
            g.drawString("FPS " + fps, WIDTH - 90, HEIGHT - 270);
        }

        static String ordinalSuffix(int n) {
            int v = n % 100;
            if (v >= 11 && v <= 13) return "TH";
            switch (n % 10) {
                case 1: return "ST";
                case 2: return "ND";
                case 3: return "RD";
                default: return "TH";
            }
        }
    }

    // ====================== COLOR UTILS ======================
    static Color darker(Color c, double f) {
        return new Color(
            clamp((int)(c.getRed()   * f)),
            clamp((int)(c.getGreen() * f)),
            clamp((int)(c.getBlue()  * f)),
            c.getAlpha());
    }
    static Color brighter(Color c, double f) {
        return new Color(
            clamp((int)(c.getRed()   * f)),
            clamp((int)(c.getGreen() * f)),
            clamp((int)(c.getBlue()  * f)),
            c.getAlpha());
    }
    static Color lerpColor(Color a, Color b, float t) {
        float it = 1 - t;
        return new Color(
            clamp((int)(a.getRed()   * it + b.getRed()   * t)),
            clamp((int)(a.getGreen() * it + b.getGreen() * t)),
            clamp((int)(a.getBlue()  * it + b.getBlue()  * t)));
    }
    static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
