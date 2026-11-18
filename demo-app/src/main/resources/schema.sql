CREATE TABLE IF NOT EXISTS demo_applications(id BIGINT PRIMARY KEY,owner VARCHAR(80) NOT NULL,student_name VARCHAR(120) NOT NULL,course VARCHAR(160) NOT NULL);
CREATE TABLE IF NOT EXISTS demo_uploads(id VARCHAR(36) PRIMARY KEY,owner VARCHAR(80) NOT NULL,path VARCHAR(1000) NOT NULL,original_name VARCHAR(255) NOT NULL,content_type VARCHAR(255));
INSERT IGNORE INTO demo_applications(id,owner,student_name,course) VALUES (1,'user-a','张同学','AI 入门'),(2,'user-b','李同学','Web 安全');
