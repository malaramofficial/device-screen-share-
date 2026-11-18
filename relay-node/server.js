const http = require("http");
const { WebSocketServer, WebSocket } = require("ws");

const PORT = process.env.PORT || 8080;

// Map<roomCode, { host: WebSocket | null, friend: WebSocket | null, pin: string }>
const rooms = new Map();

const server = http.createServer((req, res) => {
  if (req.url === "/health" || req.url === "/") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true, service: "netsetu-relay", activeRooms: rooms.size }));
    return;
  }
  res.writeHead(404);
  res.end("Not found");
});

const wss = new WebSocketServer({ server, path: "/ws" });

function sendStatus(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

// Keep connections alive across Render / Railway / mobile NAT reverse proxies
const heartbeatInterval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) {
      ws.terminate();
      return;
    }
    ws.isAlive = false;
    ws.ping();
  });
}, 12000);

wss.on("close", () => {
  clearInterval(heartbeatInterval);
});

wss.on("connection", (ws, req) => {
  ws.isAlive = true;
  ws.on("pong", () => {
    ws.isAlive = true;
  });

  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  const role = (url.searchParams.get("role") || "").toLowerCase();
  const room = (url.searchParams.get("room") || "").trim();
  const pin = (url.searchParams.get("pin") || "").trim();

  if (!room || (role !== "host" && role !== "friend")) {
    ws.close(1008, "Invalid role or room");
    return;
  }

  let entry = rooms.get(room);
  if (!entry) {
    entry = { host: null, friend: null, pin };
    rooms.set(room, entry);
  }

  if (role === "host") {
    if (entry.host && entry.host.readyState === WebSocket.OPEN) {
      entry.host.close(1000, "Replaced by new host");
    }
    entry.host = ws;
    entry.pin = pin;
    const friendAlive = Boolean(entry.friend && entry.friend.readyState === WebSocket.OPEN);
    sendStatus(ws, { type: "status", connected: true, peerOnline: friendAlive });
    if (friendAlive) {
      sendStatus(entry.friend, { type: "status", connected: true, peerOnline: true });
    }
  } else {
    if (!entry.host || entry.host.readyState !== WebSocket.OPEN) {
      sendStatus(ws, { type: "error", message: "Host is offline for this room" });
      ws.close(1008, "Host offline");
      return;
    }
    if (entry.pin && entry.pin !== pin) {
      sendStatus(ws, { type: "error", message: "Incorrect PIN" });
      ws.close(1008, "Wrong PIN");
      return;
    }
    if (entry.friend && entry.friend.readyState === WebSocket.OPEN) {
      entry.friend.close(1000, "Replaced by new friend");
    }
    entry.friend = ws;
    sendStatus(ws, { type: "status", connected: true, peerOnline: true });
    sendStatus(entry.host, { type: "status", connected: true, peerOnline: true });
  }

  ws.on("message", (data, isBinary) => {
    if (!isBinary) return;
    const peer = role === "host" ? entry.friend : entry.host;
    if (peer && peer.readyState === WebSocket.OPEN) {
      peer.send(data, { binary: true });
    } else {
      sendStatus(ws, { type: "status", connected: true, peerOnline: false });
    }
  });

  const cleanup = () => {
    const current = rooms.get(room);
    if (!current) return;
    if (role === "host" && current.host === ws) {
      current.host = null;
      if (current.friend) {
        sendStatus(current.friend, { type: "status", connected: true, peerOnline: false });
      }
    } else if (role === "friend" && current.friend === ws) {
      current.friend = null;
      if (current.host) {
        sendStatus(current.host, { type: "status", connected: true, peerOnline: false });
      }
    }
    if (!current.host && !current.friend) {
      rooms.delete(room);
    }
  };

  ws.on("close", cleanup);
  ws.on("error", cleanup);
});

server.listen(PORT, () => {
  console.log(`NetSetu Relay running on port ${PORT}`);
});
