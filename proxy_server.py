#!/usr/bin/env python3
import http.server
import socketserver
import urllib.request
import urllib.parse
from urllib.error import URLError, HTTPError

PORT = 5000

class ProxyHTTPRequestHandler(http.server.SimpleHTTPRequestHandler):
    
    def do_GET(self):
        if self.path.startswith('/proxy?url='):
            self.handle_proxy()
        else:
            super().do_GET()
    
    def handle_proxy(self):
        try:
            parsed_path = urllib.parse.urlparse(self.path)
            query_params = urllib.parse.parse_qs(parsed_path.query)
            
            if 'url' not in query_params:
                self.send_error(400, "Missing 'url' parameter")
                return
            
            target_url = query_params['url'][0]
            
            print(f"Proxying request to: {target_url}")
            
            headers = {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Accept': '*/*',
                'Accept-Language': 'en-US,en;q=0.9,ru;q=0.8',
                'Accept-Encoding': 'identity',
                'Connection': 'keep-alive',
                'Referer': target_url.rsplit('/', 1)[0] + '/'
            }
            
            req = urllib.request.Request(target_url, headers=headers)
            
            with urllib.request.urlopen(req, timeout=30) as response:
                content = response.read()
                content_type = response.headers.get('Content-Type', 'application/vnd.apple.mpegurl')
                
                if content_type == 'application/vnd.apple.mpegurl' or target_url.endswith('.m3u8'):
                    content_str = content.decode('utf-8', errors='ignore')
                    
                    lines = content_str.split('\n')
                    modified_lines = []
                    for line in lines:
                        stripped = line.strip()
                        if stripped and not stripped.startswith('#'):
                            if stripped.startswith('http://') or stripped.startswith('https://'):
                                modified_lines.append(f'/proxy?url={urllib.parse.quote(stripped, safe="")}')
                            elif not stripped.startswith('/'):
                                base_url = target_url.rsplit('/', 1)[0]
                                full_url = f'{base_url}/{stripped}'
                                modified_lines.append(f'/proxy?url={urllib.parse.quote(full_url, safe="")}')
                            else:
                                modified_lines.append(line)
                        else:
                            modified_lines.append(line)
                    
                    content = '\n'.join(modified_lines).encode('utf-8')
                
                self.send_response(200)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Length', str(len(content)))
                self.send_header('Access-Control-Allow-Origin', '*')
                self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
                self.send_header('Access-Control-Allow-Headers', '*')
                self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
                self.send_header('Pragma', 'no-cache')
                self.send_header('Expires', '0')
                self.end_headers()
                
                self.wfile.write(content)
                print(f"Successfully proxied {len(content)} bytes")
                
        except HTTPError as e:
            print(f"HTTP Error: {e.code} - {e.reason}")
            self.send_error(e.code, f"Upstream error: {e.reason}")
        except URLError as e:
            print(f"URL Error: {e.reason}")
            self.send_error(502, f"Cannot reach upstream: {e.reason}")
        except Exception as e:
            print(f"Error: {str(e)}")
            import traceback
            traceback.print_exc()
            self.send_error(500, f"Proxy error: {str(e)}")
    
    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*')
        self.end_headers()
    
    def log_message(self, format, *args):
        if args and isinstance(args[0], str) and '/proxy?' in args[0]:
            return
        super().log_message(format, *args)

Handler = ProxyHTTPRequestHandler

class ReusableTCPServer(socketserver.TCPServer):
    allow_reuse_address = True

with ReusableTCPServer(("0.0.0.0", PORT), Handler) as httpd:
    print(f"Proxy server running on port {PORT}")
    httpd.serve_forever()
