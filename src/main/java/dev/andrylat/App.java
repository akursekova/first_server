package dev.andrylat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class App {
    private static Logger LOGGER = LogManager.getLogger(App.class);

    private static final int PORT = System.getenv("PORT") != null
            ? Integer.parseInt(System.getenv("PORT"))
            : 8765;

    public static void main(String[] args) {
        LOGGER.info("Server is starting...");

        HttpServer server;
        try {
            server = HttpServer.create();
            //bind port to the server
            server.bind(new InetSocketAddress(PORT), 0);
        } catch (IOException ex) {
            LOGGER.error("Server can't start! Reason: {}", ex.getMessage(), ex);
            return;
        }

        //на сервере может быть миллион end points
        //context - это какой-то путь и у каждого пути есть обработчик handle. И в этом handle  я должна описать как будет обработан запрос.
        //handle принимает на вход HttpExchange exchange. Это данные которые пришли мне (о запросе и ответе: реквест и респонс).
        // Из этого объекта я могу вынуть много инфы. И соотв-но могу отправить ответ.
        //как работает: он берет start with "/"  и передает обработчику. Но в этом случае надо описать каждый случай который у нас есть (лекция от Андрея). В результате обработчик будет огромным.
        // Поэтому  у нас еще есть "/devices", "/cables"
        server.createContext("/", new PingHandler());
        server.createContext("/devices", new DevicesHandler());
        server.createContext("/cables", new CablesHandler());
        server.start();
    }

}

class PingHandler implements HttpHandler {

    private static Logger LOGGER = LogManager.getLogger(PingHandler.class);

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        LOGGER.trace("Ping request. \n Method: {} \n Headers: {} \n Body: {}",
                exchange.getRequestMethod(), exchange.getRequestHeaders(), new String(exchange.getRequestBody().readAllBytes()));
        LOGGER.trace("User data. IP: {}, Protocol: {}, URI: {}",
                exchange.getRemoteAddress(), exchange.getProtocol(), exchange.getRequestURI());

        String pingMessage = "{\"ping\": \"success\"}";
        exchange.sendResponseHeaders(200, pingMessage.getBytes().length);
        exchange.getResponseBody().write(pingMessage.getBytes());
        exchange.getResponseBody().flush();

        LOGGER.debug("Ping finished!");
    }
}

class DevicesHandler implements HttpHandler {
    private static Logger LOGGER = LogManager.getLogger(DevicesHandler.class);

    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        LOGGER.trace("Devices request. \n Method: {} \n Headers: {}",
                exchange.getRequestMethod(), exchange.getRequestHeaders());
        LOGGER.trace("User data. IP: {}, Protocol: {}, URI: {}",
                exchange.getRemoteAddress(), exchange.getProtocol(), exchange.getRequestURI());

        switch (exchange.getRequestMethod()) {
            case "GET":
                processGet(exchange);
                break;
            case "POST":
                processPost(exchange);
                break;
            case "DELETE":
                processDelete(exchange);
                break;
        }
    }

    private void processGet(HttpExchange exchange) throws IOException {
        String url = exchange.getRequestURI().toString();
        LOGGER.trace("GET url = {}", url);

        if (url.endsWith("/ports")) {
            String deviceIdValue = url.replaceAll("\\D+", "");
            LOGGER.trace("deviceIdValue: {}", deviceIdValue);
            if (deviceIdValue.isEmpty()) {
                nok(exchange, "URL doesn't contain deviceId. URL: " + url);
            }

            try {
                long deviceId = Long.parseLong(deviceIdValue);
                List<Port> ports = Repository.fetchAllPortsByDevice(deviceId);
                String resultJson = mapper.writeValueAsString(ports);
                ok(exchange, 200, resultJson);
            } catch (Exception ex) {
                nok(exchange, ex.getMessage());
            }
        }
        if (url.equals("/devices")) {
            try {
                List<Device> devices = Repository.fetchAllDevices();
                String resultJson = mapper.writeValueAsString(devices);
                ok(exchange, 200, resultJson);
            } catch (Exception ex) {
                nok(exchange, ex.getMessage());
            }
        } else {
            String deviceIdValue = url.substring(url.lastIndexOf('/') + 1);
            LOGGER.trace("deviceIdValue: {}", deviceIdValue);
            if (deviceIdValue.isEmpty()) {
                nok(exchange, "URL doesn't contain deviceId. URL: " + url);
            }

            try {
                long deviceId = Long.parseLong(deviceIdValue);
                Device device = Repository.fetchDevice(deviceId);
                String resultJson = mapper.writeValueAsString(device);
                ok(exchange, 200, resultJson);
            } catch (Exception ex) {
                nok(exchange, ex.getMessage());
            }
        }
    }

    private void processPost(HttpExchange exchange) throws IOException {
        String url = exchange.getRequestURI().toString();
        LOGGER.trace("POST url = {}", url);

        if (url.endsWith("/ports")) {
            String deviceIdValue = url.replaceAll("\\D+", "");
            LOGGER.trace("deviceIdValue: {}", deviceIdValue);
            if (deviceIdValue.isEmpty()) {
                nok(exchange, "URL doesn't contain deviceId. URL: " + url);
            }

            try {
                long deviceId = Long.parseLong(deviceIdValue);

                String portJson = new String(exchange.getRequestBody().readAllBytes());
                Port port = mapper.readValue(portJson, Port.class);

                Repository.addDevicePort(deviceId, port);

                String resultJson = mapper.writeValueAsString(port);
                ok(exchange, 200, resultJson);
            } catch (Exception ex) {
                nok(exchange, ex.getMessage());
            }
        } else if (url.equals("/devices")) {
            LOGGER.trace("try add new device");
            try {
                String deviceJson = new String(exchange.getRequestBody().readAllBytes());
                LOGGER.trace("Device data: {}", deviceJson);
                Device device = mapper.readValue(deviceJson, Device.class);

                Repository.addDevice(device);

                LOGGER.trace("Device added: {}", device);
                String resultJson = mapper.writeValueAsString(device);
                ok(exchange, 200, resultJson);
            } catch (Exception ex) {
                LOGGER.error(ex);
                nok(exchange, ex.getMessage());
            }
        } else {
            nok(exchange, "Can't map URL");
        }
    }

    private void processDelete(HttpExchange exchange) throws IOException {
        String url = exchange.getRequestURI().toString();
        LOGGER.trace("DELETE url = {}", url);

        String deviceIdValue = url.substring(url.lastIndexOf('/') + 1);
        LOGGER.trace("deviceIdValue: {}", deviceIdValue);
        if (deviceIdValue.isEmpty()) {
            nok(exchange, "URL doesn't contain deviceId. URL: " + url);
        }

        try {
            long deviceId = Long.parseLong(deviceIdValue);
            Device device = Repository.removeDevice(deviceId);

            String resultJson = mapper.writeValueAsString(device);
            ok(exchange, 200, resultJson);
        } catch (Exception ex) {
            nok(exchange, ex.getMessage());
        }
    }

    private void nok(HttpExchange exchange, String reason) throws IOException {
        try {
            String errorMessage = mapper.writeValueAsString(new ErrorMessage(reason));

            ok(exchange, 400, errorMessage);
        } catch (Exception e) {
            exchange.sendResponseHeaders(503, 0);
        }
    }

    private void ok(HttpExchange exchange, int rCode, String resultJson) throws IOException {
        exchange.sendResponseHeaders(rCode, resultJson.getBytes().length);
        exchange.getResponseBody().write(resultJson.getBytes());
        exchange.getResponseBody().flush();
        exchange.getResponseBody().close();
    }
}

class CablesHandler implements HttpHandler {
    private static Logger LOGGER = LogManager.getLogger(CablesHandler.class);

    private ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        LOGGER.trace("Cables request. \n Method: {} \n Headers: {}",
                exchange.getRequestMethod(), exchange.getRequestHeaders());
        LOGGER.trace("User data. IP: {}, Protocol: {}, URI: {}",
                exchange.getRemoteAddress(), exchange.getProtocol(), exchange.getRequestURI());

        switch (exchange.getRequestMethod()) {
            case "GET":
                processGet(exchange);
                break;
            case "POST":
                processPost(exchange);
                break;
            case "DELETE":
                nok(exchange, "Doesnt support!");
                break;
        }
    }

    private void processGet(HttpExchange exchange) throws IOException {
        String url = exchange.getRequestURI().toString();
        LOGGER.trace("GET url = {}", url);

        if (url.equals("/cables")) {
            try {
                List<Cable> cables = Repository.fetchAllCables();
                String resultJson = mapper.writeValueAsString(cables);
                ok(exchange, 200, resultJson);
            } catch (Exception ex) {
                nok(exchange, ex.getMessage());
            }
        } else {
            //cable by Device ID, not cable ID
            String cableIdValue = url.substring(url.lastIndexOf('/') + 1);
            LOGGER.trace("cableIdValue: {}", cableIdValue);
            if (cableIdValue.isEmpty()) {
                nok(exchange, "URL doesn't contain cableId. URL: " + url);
            }

            try {
                long cableId = Long.parseLong(cableIdValue);
                Device device = Repository.fetchDevice(cableId);
                String resultJson = mapper.writeValueAsString(device);
                ok(exchange, 200, resultJson);
            } catch (Exception ex) {
                nok(exchange, ex.getMessage());
            }
        }
    }

    private void processPost(HttpExchange exchange) throws IOException {
        String url = exchange.getRequestURI().toString();
        LOGGER.trace("POST url = {}", url);

        if (url.equals("/cables")) {
            LOGGER.trace("try add new cable");
            try {
                String cableJson = new String(exchange.getRequestBody().readAllBytes());
                LOGGER.trace("Cable data: {}", cableJson);
                Cable cable = mapper.readValue(cableJson, Cable.class);

                Repository.addCable(cable);

                LOGGER.trace("Cable added: {}", cable);
                String resultJson = mapper.writeValueAsString(cable);
                ok(exchange, 200, resultJson);
            } catch (Exception ex) {
                LOGGER.error(ex);
                nok(exchange, ex.getMessage());
            }
        } else {
            nok(exchange, "Can't map URL");
        }
    }

    private void nok(HttpExchange exchange, String reason) throws IOException {
        try {
            String errorMessage = mapper.writeValueAsString(new ErrorMessage(reason));

            ok(exchange, 400, errorMessage);
        } catch (Exception e) {
            exchange.sendResponseHeaders(503, 0);
        }
    }

    private void ok(HttpExchange exchange, int rCode, String resultJson) throws IOException {
        exchange.sendResponseHeaders(rCode, resultJson.getBytes().length);
        exchange.getResponseBody().write(resultJson.getBytes());
        exchange.getResponseBody().flush();
        exchange.getResponseBody().close();
    }
}

class Repository {
    private static AtomicLong deviceIdSequence = new AtomicLong(0L);
    private static AtomicLong portIdSequence = new AtomicLong(0L);
    private static AtomicLong cableIdSequence = new AtomicLong(0L);


    private static Map<Long, Device> devices = new ConcurrentHashMap<>();
    private static Map<Long, Cable> cables = new ConcurrentHashMap<>();

    public static Device addDevice(Device device) {
        device.setId(deviceIdSequence.incrementAndGet());

        return devices.put(device.getId(), device);
    }

    public static Port addDevicePort(long deviceId, Port port) {
        Device device = devices.get(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("Device with ID = " + deviceId + "is not found!");
        }

        port.setId(portIdSequence.incrementAndGet());

        device.addPort(port);

        return port;
    }

    public static Device fetchDevice(long deviceId) {
        Device device = devices.get(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("Device with ID = " + deviceId + "is not found!");
        }

        return device;
    }

    public static List<Device> fetchAllDevices() {
        return List.copyOf(devices.values());
    }

    public static List<Port> fetchAllPortsByDevice(long deviceId) {
        Device device = devices.get(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("Device with ID = " + deviceId + "is not found!");
        }

        return List.copyOf(device.getPorts());
    }

    public static Device removeDevice(long deviceId) {
        return devices.remove(deviceId);
    }

    public static Cable addCable(Cable cable) {
        if (!devices.containsKey(cable.getDeviceAId())) {
            throw new IllegalArgumentException("Device with DeviceAId = " + cable.getDeviceAId() + "is not found!");
        }

        if (!devices.containsKey(cable.getDeviceZId())) {
            throw new IllegalArgumentException("Device with DeviceZId = " + cable.getDeviceZId() + "is not found!");
        }

        cable.setId(cableIdSequence.incrementAndGet());
        return cables.put(cable.getId(), cable);
    }

    public static List<Cable> fetchAllCables() {
        return List.copyOf(cables.values());
    }

    public static Cable fetchCable(long cableId) {
        Cable cable = cables.get(cableId);
        if (cable == null) {
            throw new IllegalArgumentException("Cable with ID = " + cableId + "is not found!");
        }

        return cable;
    }

}

class Device {
    private long id;
    private String name;
    private String model;
    private boolean isActive;

    @JsonIgnore
    private List<Port> ports = new ArrayList<>();

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setIsActive(boolean active) {
        isActive = active;
    }

    public void addPort(Port port) {
        this.ports.add(port);
    }

    public List<Port> getPorts() {
        return List.copyOf(this.ports);
    }
}

class Port {
    private long id;
    private String name;
    private String type;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}

class Cable {
    private long id;
    private String name;
    private long deviceAId;
    private long deviceZId;
    private double length;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getDeviceAId() {
        return deviceAId;
    }

    public void setDeviceAId(long deviceAId) {
        this.deviceAId = deviceAId;
    }

    public long getDeviceZId() {
        return deviceZId;
    }

    public void setDeviceZId(long deviceZId) {
        this.deviceZId = deviceZId;
    }

    public double getLength() {
        return length;
    }

    public void setLength(double length) {
        this.length = length;
    }
}

class ErrorMessage {
    private String reason;

    public ErrorMessage(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}