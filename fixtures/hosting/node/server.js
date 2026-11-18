import http from 'node:http';
import mysql from 'mysql2/promise';
import pg from 'pg';
const isMysql=(process.env.DATABASE_URL||'').startsWith('mysql:');
const db=process.env.DB_HOST?(isMysql?await mysql.createConnection({host:process.env.DB_HOST,user:process.env.DB_USER,password:process.env.DB_PASSWORD,database:process.env.DB_NAME}):new pg.Client({connectionString:process.env.DATABASE_URL})):null;
if(db&&!isMysql)await db.connect();
if(db)await db.query('CREATE TABLE IF NOT EXISTS visits (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)');
if(db){if(isMysql)await db.query('INSERT IGNORE INTO visits VALUES(1,0)');else await db.query('INSERT INTO visits VALUES(1,0) ON CONFLICT DO NOTHING')}
http.createServer(async(req,res)=>{
 try{
 if(req.url==='/api/count'){if(req.method==='POST')await db.query('UPDATE visits SET value=value+1 WHERE id=1');const result=await db.query('SELECT value FROM visits WHERE id=1');res.setHeader('Content-Type','application/json');res.end(JSON.stringify({count:isMysql?result[0][0].value:result.rows[0].value}));return}
 if(req.url==='/api/headers'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify(req.headers));return}
 res.setHeader('Content-Type','text/html;charset=utf-8');res.end('<!doctype html><html lang="zh-CN"><head><title>Node 托管验收</title><meta name="viewport" content="width=device-width"></head><body><h1>Node 网站运行成功</h1><p>数据库与测试环境隔离</p></body></html>');
 }catch(e){res.statusCode=500;res.end('operation failed')}
}).listen(Number(process.env.PORT||3000),'0.0.0.0');
