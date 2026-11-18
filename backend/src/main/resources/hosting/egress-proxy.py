import http.server, socketserver, socket, ipaddress, urllib.parse, urllib.request, select, json, time, os

parent=os.environ.get('SHIJIE_PARENT_PROXY','')
if parent and '://' not in parent: parent='http://'+parent
opener=urllib.request.build_opener(urllib.request.ProxyHandler({'https':parent} if parent else {}))

cache = {}
def public_addresses(host):
    try: return [str(ipaddress.ip_address(host))]
    except ValueError: pass
    if host in cache and cache[host][0] > time.monotonic(): return cache[host][1]
    # The resolver is a fixed TLS endpoint; its answer is independent of local DNS rewrites.
    query = 'https://cloudflare-dns.com/dns-query?name='+urllib.parse.quote(host,safe='')+'&type=A'
    request = urllib.request.Request(query, headers={'Accept':'application/dns-json'})
    for attempt in range(3):
        try:
            with opener.open(request, timeout=10) as response: data=json.load(response)
            break
        except OSError:
            if attempt==2: raise
    result=[a['data'] for a in data.get('Answer',[]) if a.get('type')==1]
    cache[host]=(time.monotonic()+60,result)
    if len(cache)>2048: cache.clear()
    return result

def destination(host, port):
    if port not in (80, 443): raise ValueError('port denied')
    addresses = public_addresses(host)
    if not addresses or any(not ipaddress.ip_address(a).is_global for a in addresses):
        raise ValueError('private address denied')
    if parent:
        proxy=urllib.parse.urlsplit(parent)
        connection=socket.create_connection((proxy.hostname,proxy.port or 80),10)
        endpoint=addresses[0]+':'+str(port)
        connection.sendall(('CONNECT '+endpoint+' HTTP/1.1\r\nHost: '+endpoint+'\r\n\r\n').encode('ascii'))
        response=b''
        while not response.endswith(b'\r\n\r\n'):
            part=connection.recv(1)
            if not part or len(response)>8192: connection.close(); raise ValueError('proxy unavailable')
            response+=part
        if response.split(b' ')[1]!=b'200': connection.close(); raise ValueError('proxy refused')
        return connection
    for address in addresses:
        connection = socket.socket(socket.AF_INET6 if ':' in address else socket.AF_INET, socket.SOCK_STREAM)
        connection.settimeout(10)
        try:
            connection.connect((address, port))
            return connection
        except OSError:
            connection.close()
    raise ValueError('destination unreachable')

class Proxy(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_CONNECT(self):
        try:
            host, port = self.path.rsplit(':', 1)
            with destination(host, int(port)) as upstream:
                self.send_response(200); self.end_headers()
                self.connection.setblocking(False); upstream.setblocking(False)
                while True:
                    ready, _, _ = select.select([self.connection, upstream], [], [], 120)
                    if not ready: break
                    for source in ready:
                        data = source.recv(65536)
                        if not data: return
                        target = upstream if source is self.connection else self.connection
                        target.setblocking(True); target.sendall(data); target.setblocking(False)
        except Exception: self.send_error(403, 'Destination unavailable or denied')
    def do_GET(self): self.forward()
    def do_HEAD(self): self.forward()
    def forward(self):
        try:
            url = urllib.parse.urlsplit(self.path)
            if url.scheme != 'http' or url.username or url.password: raise ValueError()
            with destination(url.hostname, url.port or 80) as upstream:
                path = (url.path or '/') + (('?' + url.query) if url.query else '')
                request = f'{self.command} {path} HTTP/1.1\r\nHost: {url.netloc}\r\nConnection: close\r\n'
                for key, value in self.headers.items():
                    if key.lower() not in ('host','connection','proxy-connection','proxy-authorization'):
                        request += f'{key}: {value}\r\n'
                upstream.sendall((request+'\r\n').encode('iso-8859-1'))
                while True:
                    data = upstream.recv(65536)
                    if not data: break
                    self.wfile.write(data)
        except Exception: self.send_error(403, 'Destination unavailable or denied')

class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True

Server(('0.0.0.0', 3128), Proxy).serve_forever()
