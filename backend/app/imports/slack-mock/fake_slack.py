"""
A fake Slack Web API for the sandbox application.

Stands in for `https://slack.com/api` so the plugin can be exercised end to end without a
workspace, a real app or a token: the sandbox posts messages to it, and messages typed into
its web UI come back out of `conversations.history` for the poller to turn into cases.

Stateful on purpose. A static stub (WireMock and friends) can answer `chat.postMessage`, but
the inbound half of the plugin only becomes visible when a message that was posted, or typed,
is actually *there* on the next read — including the thread it belongs to, the cursor it sits
behind, and the `bot_id` that says the case wrote it rather than a person.

Implemented with the standard library alone so the container is a stock `python:*-alpine` with
this file mounted into it, and nothing has to be built or published to run the sandbox.

Endpoints
---------
Slack, as the plugin uses them (`Authorization: Bearer <token>` required):

    POST /api/chat.postMessage        multipart, form-encoded or JSON
    POST /api/files.upload            multipart
    GET  /api/conversations.history
    GET  /api/conversations.replies

The harness, which real Slack has no equivalent of (no authentication — it is the test rig):

    GET  /                           web UI: read the channel, type into it
    POST /_fake/messages             inject a message as if a person typed it
    GET  /_fake/state                every channel and message held right now
    POST /_fake/reset                forget every message, keep the channels
    GET  /_fake/health               container healthcheck

Faithful where it matters
-------------------------
- A failed call is `200 OK` with `{"ok": false, "error": "..."}`, which is how Slack reports
  every application-level failure. Code that switches on the status code sees success.
- `conversations.history` answers newest first and only returns top-level messages; a reply
  inside a thread is reachable through `conversations.replies` alone. That split is the whole
  reason the poller does two reads per channel.
- `conversations.replies` answers oldest first, with the thread parent as the first message.
- `oldest` is honoured, exclusively unless `inclusive=true`, and paging is real: `limit`,
  `has_more` and an opaque `response_metadata.next_cursor`.
- A message posted through `chat.postMessage` carries `bot_id` and `app_id`, exactly as one
  posted by an app does. It is what makes the sandbox show the loop the plugin guards against:
  without `includeBotMessages`, a case's own message is not allowed to start another case.

Environment
-----------
    SLACK_MOCK_PORT       port to listen on (default 8080)
    SLACK_MOCK_TOKEN      the only accepted bearer token; any non-empty token if unset
    SLACK_MOCK_CHANNELS   channels to seed, `id:name` comma-separated
    SLACK_MOCK_BOT_ID     bot id stamped on messages this app posts
    SLACK_MOCK_APP_ID     app id stamped on messages this app posts
    SLACK_MOCK_BOT_USER   user id stamped on messages this app posts
    SLACK_MOCK_USER_ID    default author of an injected message
    SLACK_MOCK_USER_NAME  default author name of an injected message
"""

import json
import os
import re
import threading
import time
from email.parser import BytesParser
from email.policy import default as default_policy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(os.environ.get("SLACK_MOCK_PORT", "8080"))
EXPECTED_TOKEN = os.environ.get("SLACK_MOCK_TOKEN", "").strip()
SEED_CHANNELS = os.environ.get("SLACK_MOCK_CHANNELS", "C01SANDBOX:sandbox")
BOT_ID = os.environ.get("SLACK_MOCK_BOT_ID", "B0SANDBOXBOT")
APP_ID = os.environ.get("SLACK_MOCK_APP_ID", "A0SANDBOXAPP")
BOT_USER_ID = os.environ.get("SLACK_MOCK_BOT_USER", "U0SANDBOXBOT")
DEFAULT_USER_ID = os.environ.get("SLACK_MOCK_USER_ID", "U0SANDBOXHUM")
DEFAULT_USER_NAME = os.environ.get("SLACK_MOCK_USER_NAME", "sandbox.user")

# Slack's own ceiling for the `limit` parameter of the conversations methods.
MAX_LIMIT = 1000
DEFAULT_LIMIT = 100


class Store:
    """
    Every channel and its messages, oldest first.

    Held in memory: restarting the container is how you get a clean workspace, and the
    channel cursors the plugin keeps in Postgres survive that restart — which is itself worth
    seeing, because a cursor pointing past everything the mock still knows about is exactly
    the state a re-created Slack workspace would leave behind.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._channels = {}
        self._messages = {}
        # Slack's `ts` doubles as the message id and has to be unique within a channel, so a
        # burst of posts inside the same microsecond may not collide.
        self._last_ts = 0.0

    def seed(self, specification):
        for entry in filter(None, (part.strip() for part in specification.split(","))):
            channel_id, _, name = entry.partition(":")
            self.ensure_channel(channel_id.strip(), (name or channel_id).strip())

    def ensure_channel(self, channel_id, name=None):
        with self._lock:
            channel = self._channels.get(channel_id)
            if channel is None:
                channel = {"id": channel_id, "name": name or channel_id}
                self._channels[channel_id] = channel
                self._messages[channel_id] = []
            return channel

    def resolve(self, specification):
        """
        Returns the channel id for an id, a `#name` or a bare name, or `None`.

        Names are accepted because `chat.postMessage` accepts them and because a sandbox is
        friendlier when `#sandbox` works; the read methods are handed whatever this returns,
        so a link configured with a name still finds its channel here.
        """
        if not specification:
            return None
        candidate = specification.strip().lstrip("#")
        with self._lock:
            if candidate in self._channels:
                return candidate
            for channel_id, channel in self._channels.items():
                if channel["name"] == candidate:
                    return channel_id
        return None

    def channels(self):
        with self._lock:
            return [dict(channel) for channel in self._channels.values()]

    def messages(self, channel_id):
        with self._lock:
            return [dict(message) for message in self._messages.get(channel_id, [])]

    def find(self, channel_id, ts):
        with self._lock:
            for message in self._messages.get(channel_id, []):
                if message["ts"] == ts:
                    return dict(message)
        return None

    def add(self, channel_id, message):
        with self._lock:
            now = time.time()
            self._last_ts = max(now, self._last_ts + 0.000001)
            stored = dict(message)
            stored["type"] = "message"
            stored["ts"] = "%.6f" % self._last_ts
            # A thread parent reports the thread it opened, the same way Slack does, so
            # `thread_ts == ts` distinguishes it from a reply.
            if stored.get("thread_ts") is None:
                stored.pop("thread_ts", None)
            self._messages.setdefault(channel_id, []).append(stored)
            return dict(stored)

    def reset(self):
        with self._lock:
            for channel_id in self._messages:
                self._messages[channel_id] = []


STORE = Store()


def error(code):
    return {"ok": False, "error": code}


def page(messages, limit, cursor):
    """
    Cuts one page out of `messages` and reports how to ask for the next.

    The cursor is an offset dressed up as an opaque string, because a client that parses it
    instead of echoing it back would work here and break against Slack.
    """
    offset = 0
    if cursor:
        match = re.fullmatch(r"offset:(\d+)", cursor)
        if match is None:
            return None, None
        offset = int(match.group(1))

    window = messages[offset:offset + limit]
    remaining = len(messages) > offset + limit
    return window, ("offset:%d" % (offset + limit) if remaining else None)


def in_window(ts, oldest, latest, inclusive):
    value = float(ts)
    if oldest is not None:
        if value < oldest or (value == oldest and not inclusive):
            return False
    if latest is not None:
        if value > latest or (value == latest and not inclusive):
            return False
    return True


def as_float(value):
    try:
        return float(value) if value not in (None, "") else None
    except ValueError:
        return None


def as_bool(value, fallback=False):
    if value is None:
        return fallback
    return str(value).strip().lower() in ("1", "true", "yes")


def as_limit(value):
    limit = DEFAULT_LIMIT
    try:
        if value not in (None, ""):
            limit = int(value)
    except ValueError:
        return DEFAULT_LIMIT
    return max(1, min(limit, MAX_LIMIT))


def public_message(message):
    """The message as Slack serialises it in a conversations response."""
    return {key: value for key, value in message.items() if value is not None}


class Handler(BaseHTTPRequestHandler):
    # Keep-alive, because the plugin's RestClient reuses connections and a poll of a paged
    # channel is several requests in a row.
    protocol_version = "HTTP/1.1"
    server_version = "fake-slack/1.0"

    # -- routing ----------------------------------------------------------------------

    def do_GET(self):
        self._consume_body()
        route = urlparse(self.path)
        query = {key: values[-1] for key, values in parse_qs(route.query).items()}

        if route.path in ("/", "/index.html"):
            return self._send_html(UI_HTML)
        if route.path == "/_fake/health":
            return self._send_json({"ok": True, "channels": len(STORE.channels())})
        if route.path == "/_fake/state":
            return self._send_json(self._state(query.get("channel")))
        if route.path == "/api/conversations.history":
            return self._authenticated(lambda: self._conversations_history(query))
        if route.path == "/api/conversations.replies":
            return self._authenticated(lambda: self._conversations_replies(query))
        if route.path.startswith("/api/"):
            return self._send_json(error("unknown_method"))
        return self._send_json(error("not_found"), status=404)

    def do_POST(self):
        body, content_type = self._read_body()
        route = urlparse(self.path)

        if route.path == "/_fake/messages":
            return self._send_json(self._inject(self._parse_body(body, content_type)))
        if route.path == "/_fake/reset":
            STORE.reset()
            return self._send_json({"ok": True})
        if route.path == "/api/chat.postMessage":
            form = self._parse_body(body, content_type)
            return self._authenticated(lambda: self._chat_post_message(form))
        if route.path == "/api/files.upload":
            form = self._parse_body(body, content_type)
            return self._authenticated(lambda: self._files_upload(form))
        if route.path.startswith("/api/"):
            return self._send_json(error("unknown_method"))
        return self._send_json(error("not_found"), status=404)

    # -- Slack methods ----------------------------------------------------------------

    def _chat_post_message(self, form):
        channel_id = STORE.resolve(form.get("channel"))
        if channel_id is None:
            return error("channel_not_found")

        text = form.get("text")
        if not text:
            return error("no_text")

        thread_ts = form.get("thread_ts") or None
        if thread_ts and STORE.find(channel_id, thread_ts) is None:
            return error("thread_not_found")

        message = STORE.add(
            channel_id,
            {
                "text": text,
                "user": BOT_USER_ID,
                "username": form.get("username") or "Valtimo messenger",
                "bot_id": BOT_ID,
                "app_id": APP_ID,
                "thread_ts": thread_ts,
            },
        )
        self._log("posted %s in %s%s", message["ts"], channel_id,
                  " (thread %s)" % thread_ts if thread_ts else "")
        return {
            "ok": True,
            "channel": channel_id,
            "ts": message["ts"],
            "message": public_message(message),
        }

    def _files_upload(self, form):
        # `channels` is a comma-separated list, and the message may arrive under either the
        # current name (`initial_comment`) or the legacy one the plugin still sends.
        specifications = [part.strip() for part in (form.get("channels") or "").split(",")]
        channel_ids = [STORE.resolve(specification) for specification in specifications if specification]
        if not channel_ids or None in channel_ids:
            return error("channel_not_found")

        file_name = form.get("filename") or form.get("file_name") or "upload"
        content = form.get("content") or b""
        if isinstance(content, str):
            content = content.encode()

        file_id = "F%d" % int(time.time() * 1000)
        descriptor = {
            "id": file_id,
            "name": file_name,
            "title": form.get("title") or file_name,
            "filetype": form.get("filetype") or "",
            "mimetype": form.get("mimetype") or "application/octet-stream",
            "size": len(content),
        }

        for channel_id in channel_ids:
            STORE.add(
                channel_id,
                {
                    "text": form.get("initial_comment") or form.get("initial_message") or "",
                    "user": BOT_USER_ID,
                    "username": "Valtimo messenger",
                    "bot_id": BOT_ID,
                    "app_id": APP_ID,
                    "subtype": "file_share",
                    "files": [descriptor],
                },
            )
            self._log("uploaded '%s' (%d bytes) to %s", file_name, len(content), channel_id)

        return {"ok": True, "file": descriptor, "channels": channel_ids}

    def _conversations_history(self, query):
        channel_id = STORE.resolve(query.get("channel"))
        if channel_id is None:
            return error("channel_not_found")

        oldest = as_float(query.get("oldest"))
        latest = as_float(query.get("latest"))
        inclusive = as_bool(query.get("inclusive"))

        # Top-level only. A reply lives in its thread and nowhere else, which is why the
        # poller has to ask about threads separately.
        candidates = [
            message
            for message in STORE.messages(channel_id)
            if message.get("thread_ts") in (None, message["ts"])
            and in_window(message["ts"], oldest, latest, inclusive)
        ]
        candidates.reverse()

        window, next_cursor = page(candidates, as_limit(query.get("limit")), query.get("cursor"))
        if window is None:
            return error("invalid_cursor")

        self._log(
            "history of %s: %d message(s)%s", channel_id, len(window),
            " (more to come)" if next_cursor else "",
        )
        return self._paged(window, next_cursor)

    def _conversations_replies(self, query):
        channel_id = STORE.resolve(query.get("channel"))
        if channel_id is None:
            return error("channel_not_found")

        thread_ts = query.get("ts")
        parent = STORE.find(channel_id, thread_ts) if thread_ts else None
        if parent is None:
            return error("thread_not_found")

        oldest = as_float(query.get("oldest"))
        latest = as_float(query.get("latest"))
        inclusive = as_bool(query.get("inclusive"))

        replies = [
            message
            for message in STORE.messages(channel_id)
            if message.get("thread_ts") == thread_ts
            and message["ts"] != thread_ts
            and in_window(message["ts"], oldest, latest, inclusive)
        ]

        # The parent is always the first message of the answer, whatever `oldest` says, and it
        # advertises the thread it opened.
        parent["thread_ts"] = thread_ts
        parent["reply_count"] = len(replies)
        window, next_cursor = page([parent] + replies, as_limit(query.get("limit")), query.get("cursor"))
        if window is None:
            return error("invalid_cursor")

        self._log("thread %s of %s: %d reply/replies", thread_ts, channel_id, len(replies))
        return self._paged(window, next_cursor)

    def _paged(self, messages, next_cursor):
        response = {
            "ok": True,
            "messages": [public_message(message) for message in messages],
            "has_more": next_cursor is not None,
        }
        if next_cursor is not None:
            response["response_metadata"] = {"next_cursor": next_cursor}
        return response

    # -- harness ----------------------------------------------------------------------

    def _inject(self, form):
        """Adds a message as if a person had typed it: no `bot_id`, so the poller takes it."""
        specification = form.get("channel")
        channel_id = STORE.resolve(specification)
        if channel_id is None:
            if not specification:
                return error("channel_not_found")
            # Unlike the Slack methods, the harness creates what it is asked for: needing to
            # seed a channel before you can type into it would be friction with no upside.
            channel_id = STORE.ensure_channel(specification.strip().lstrip("#"))["id"]

        thread_ts = form.get("thread_ts") or None
        if thread_ts and STORE.find(channel_id, thread_ts) is None:
            return error("thread_not_found")

        message = STORE.add(
            channel_id,
            {
                "text": form.get("text") or "",
                "user": form.get("user") or DEFAULT_USER_ID,
                "username": form.get("username") or DEFAULT_USER_NAME,
                "subtype": form.get("subtype") or None,
                "thread_ts": thread_ts,
            },
        )
        self._log("injected %s in %s%s", message["ts"], channel_id,
                  " (thread %s)" % thread_ts if thread_ts else "")
        return {"ok": True, "channel": channel_id, "ts": message["ts"], "message": public_message(message)}

    def _state(self, channel):
        channels = STORE.channels()
        wanted = STORE.resolve(channel) if channel else None
        return {
            "ok": True,
            "channels": channels,
            "messages": {
                entry["id"]: [public_message(message) for message in STORE.messages(entry["id"])]
                for entry in channels
                if wanted is None or entry["id"] == wanted
            },
        }

    # -- plumbing ---------------------------------------------------------------------

    def _authenticated(self, handle):
        """
        Slack rejects an unauthenticated call with `ok: false` and HTTP 200, not with a 401.

        Worth reproducing: a plugin configuration with an empty token has to surface as a
        Slack error in the log, which is what the real thing does.
        """
        header = self.headers.get("Authorization", "")
        token = header[len("Bearer "):].strip() if header.startswith("Bearer ") else ""
        if not token:
            self._log("rejected an unauthenticated call to %s", self.path)
            return self._send_json(error("not_authed"))
        if EXPECTED_TOKEN and token != EXPECTED_TOKEN:
            self._log("rejected token '%s' on %s", token, self.path)
            return self._send_json(error("invalid_auth"))
        return self._send_json(handle())

    def _read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return (self.rfile.read(length) if length else b""), (self.headers.get("Content-Type") or "")

    def _consume_body(self):
        self._read_body()

    def _parse_body(self, body, content_type):
        """
        Reads a request body as a flat field map.

        Three encodings, because the plugin posts multipart, `curl -d` posts form-encoded and
        anything hand-written posts JSON — and a fake nobody can drive by hand is only half a
        test rig.
        """
        base_type = content_type.split(";")[0].strip().lower()

        if base_type == "multipart/form-data":
            parsed = BytesParser(policy=default_policy).parsebytes(
                b"Content-Type: " + content_type.encode() + b"\r\n\r\n" + body
            )
            fields = {}
            for part in parsed.iter_parts():
                name = part.get_param("name", header="content-disposition")
                if name is None:
                    continue
                payload = part.get_payload(decode=True) or b""
                file_name = part.get_filename()
                fields[name] = payload if name == "content" else payload.decode("utf-8", "replace")
                if file_name and not fields.get("filename"):
                    fields["filename"] = file_name
            return fields

        if base_type == "application/json":
            try:
                parsed = json.loads(body or b"{}")
            except json.JSONDecodeError:
                return {}
            return {key: value for key, value in parsed.items()} if isinstance(parsed, dict) else {}

        return {key: values[-1] for key, values in parse_qs(body.decode("utf-8", "replace")).items()}

    def _send_json(self, payload, status=200):
        self._send(status, "application/json; charset=utf-8", json.dumps(payload).encode())

    def _send_html(self, html):
        self._send(200, "text/html; charset=utf-8", html.encode())

    def _send(self, status, content_type, body):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        # The UI is served from this same origin, but a developer poking at the mock from the
        # Valtimo frontend on another port should not have to think about it.
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self._consume_body()
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _log(self, template, *arguments):
        print("fake-slack " + (template % arguments), flush=True)

    def log_message(self, template, *arguments):
        # The default access log would drown the interesting lines: the UI polls every two
        # seconds. Errors still come through `log_error`, which is separate.
        pass


UI_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Fake Slack — Valtimo sandbox</title>
<style>
  :root { color-scheme: light dark; --line: #8884; --app: #4a154b; --human: #1264a3; }
  * { box-sizing: border-box; }
  body { margin: 0; font: 14px/1.5 -apple-system, system-ui, sans-serif; display: flex;
         flex-direction: column; height: 100vh; }
  header { display: flex; gap: .75rem; align-items: center; padding: .75rem 1rem;
           border-bottom: 1px solid var(--line); }
  header h1 { font-size: 1rem; margin: 0 auto 0 0; font-weight: 600; }
  select, input, button, textarea { font: inherit; padding: .4rem .6rem; border-radius: 6px;
           border: 1px solid var(--line); background: transparent; color: inherit; }
  button { cursor: pointer; }
  main { flex: 1; overflow-y: auto; padding: 1rem; }
  ol { list-style: none; margin: 0; padding: 0; }
  li { padding: .5rem 0; border-top: 1px solid var(--line); }
  li.reply { margin-left: 2rem; }
  .who { font-weight: 600; }
  .who.app { color: var(--app); }
  .who.human { color: var(--human); }
  .badge { font-size: .7rem; text-transform: uppercase; letter-spacing: .04em; padding: 0 .35rem;
           border: 1px solid var(--line); border-radius: 4px; margin-left: .4rem; opacity: .8; }
  .meta { opacity: .55; font-size: .78rem; margin-left: .4rem; font-variant-numeric: tabular-nums; }
  .text { white-space: pre-wrap; }
  .file { opacity: .8; font-size: .85rem; }
  .empty { opacity: .6; padding: 2rem 0; text-align: center; }
  form { display: flex; gap: .5rem; padding: .75rem 1rem; border-top: 1px solid var(--line);
         align-items: center; }
  form input[type=text] { flex: 1; }
  .replying { font-size: .8rem; opacity: .7; padding: 0 1rem .5rem; }
  a { color: inherit; }
</style>
</head>
<body>
<header>
  <h1>Fake Slack</h1>
  <label>channel <select id="channel"></select></label>
  <button id="reset" type="button" title="Forget every message in every channel">Clear</button>
</header>
<main><ol id="messages"></ol></main>
<div class="replying" id="replying" hidden></div>
<form id="compose" autocomplete="off">
  <input type="text" id="text" placeholder="Type a message as a person, and watch a case start&hellip;" required>
  <button type="submit">Send</button>
</form>
<script>
  const channelSelect = document.getElementById('channel');
  const list = document.getElementById('messages');
  const replying = document.getElementById('replying');
  let threadTs = null;
  let known = '';

  const isApp = (message) => Boolean(message.bot_id || message.app_id);

  function render(state) {
    const channels = state.channels || [];
    const signature = channels.map((channel) => channel.id).join();
    if (signature !== known) {
      known = signature;
      const selected = channelSelect.value;
      channelSelect.innerHTML = channels
        .map((channel) => `<option value="${channel.id}">#${channel.name} (${channel.id})</option>`)
        .join('');
      if (channels.some((channel) => channel.id === selected)) channelSelect.value = selected;
    }

    const messages = (state.messages || {})[channelSelect.value] || [];
    if (!messages.length) {
      list.innerHTML = '<li class="empty">Nothing posted yet.</li>';
      return;
    }
    list.innerHTML = messages.map((message) => {
      const reply = message.thread_ts && message.thread_ts !== message.ts;
      const files = (message.files || []).map((file) => `📎 ${file.name}`).join(' ');
      const sent = new Date(Number(message.ts) * 1000).toLocaleTimeString();
      return `<li class="${reply ? 'reply' : ''}">
        <span class="who ${isApp(message) ? 'app' : 'human'}">${message.username || message.user || '?'}</span>
        ${isApp(message) ? '<span class="badge">app</span>' : ''}
        <span class="meta">${sent} · ${message.ts}</span>
        <button type="button" class="badge" data-thread="${message.thread_ts || message.ts}">reply</button>
        <div class="text">${(message.text || '').replace(/[<&]/g, (c) => c === '<' ? '&lt;' : '&amp;')}</div>
        ${files ? `<div class="file">${files}</div>` : ''}
      </li>`;
    }).join('');
  }

  async function refresh() {
    try {
      render(await (await fetch('/_fake/state')).json());
    } catch (error) {
      list.innerHTML = '<li class="empty">The mock is not answering.</li>';
    }
  }

  function setThread(ts) {
    threadTs = ts;
    replying.hidden = !ts;
    replying.textContent = ts ? `Replying in thread ${ts} — click Send to answer, or ` : '';
    if (ts) {
      const cancel = document.createElement('a');
      cancel.href = '#';
      cancel.textContent = 'cancel';
      cancel.onclick = (event) => { event.preventDefault(); setThread(null); };
      replying.appendChild(cancel);
    }
  }

  list.addEventListener('click', (event) => {
    const ts = event.target.dataset && event.target.dataset.thread;
    if (ts) setThread(ts);
  });

  channelSelect.addEventListener('change', () => { setThread(null); refresh(); });

  document.getElementById('reset').addEventListener('click', async () => {
    await fetch('/_fake/reset', {method: 'POST'});
    setThread(null);
    refresh();
  });

  document.getElementById('compose').addEventListener('submit', async (event) => {
    event.preventDefault();
    const input = document.getElementById('text');
    await fetch('/_fake/messages', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({channel: channelSelect.value, text: input.value, thread_ts: threadTs}),
    });
    input.value = '';
    setThread(null);
    refresh();
  });

  refresh();
  setInterval(refresh, 2000);
</script>
</body>
</html>
"""


def main():
    STORE.seed(SEED_CHANNELS)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(
        "fake-slack listening on :%d — channels %s, token %s"
        % (
            PORT,
            ", ".join(channel["id"] for channel in STORE.channels()) or "(none)",
            "'%s'" % EXPECTED_TOKEN if EXPECTED_TOKEN else "(any)",
        ),
        flush=True,
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
