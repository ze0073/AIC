import os,socket,socketserver,select
HOST=os.environ['UPSTREAM_HOST']
PORT=int(os.environ['UPSTREAM_PORT'])
if HOST not in ('backend','frontend'): raise ValueError('invalid upstream')
class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with socket.create_connection((HOST,PORT),5) as upstream:
                sockets=[self.request,upstream]
                while True:
                    ready,_,_=select.select(sockets,[],[],120)
                    if not ready:return
                    for source in ready:
                        data=source.recv(65536)
                        if not data:return
                        (upstream if source is self.request else self.request).sendall(data)
        except OSError:pass
class Server(socketserver.ThreadingMixIn,socketserver.TCPServer):
    daemon_threads=True
    allow_reuse_address=True
Server(('0.0.0.0',8080),Handler).serve_forever()
