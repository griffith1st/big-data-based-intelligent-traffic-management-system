FROM node:18-alpine AS builder

WORKDIR /app

# 只复制依赖配置文件
COPY package.json package-lock.json ./
COPY tsconfig.json ./
COPY .env ./

# 配置镜像 + 清理缓存 + 强制安装兼容依赖
RUN npm config set registry https://registry.npmmirror.com/
RUN npm cache clean --force
RUN npm ci --legacy-peer-deps --force

# 复制项目源码
COPY public/ ./public/
COPY src/ ./src/

# 构建项目
RUN npm run build

# 生产环境：Nginx部署
FROM nginx:1.25-alpine

COPY --from=builder /app/build /usr/share/nginx/html

# 覆盖Nginx配置
RUN rm /etc/nginx/conf.d/default.conf
COPY <<'EOF' /etc/nginx/conf.d/itms.conf
server {
    listen 80;
    server_name localhost;

    location / {
        root /usr/share/nginx/html;
        index index.html index.htm;
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 5s;
        proxy_read_timeout 30s;
        proxy_send_timeout 5s;
    }

    location /ws/ {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
EOF

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
