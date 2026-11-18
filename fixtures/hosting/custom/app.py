from http.server import BaseHTTPRequestHandler, HTTPServer
import os
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200);self.send_header('Content-Type','text/html;charset=utf-8');self.end_headers()
        self.wfile.write('<!doctype html><html lang="zh-CN"><head><title>自定义配置验收</title></head><body><h1>自定义网站运行成功</h1></body></html>'.encode())
HTTPServer(('0.0.0.0', int(os.environ.get('PORT','8080'))), Handler).serve_forever()
