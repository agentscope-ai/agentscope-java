# 前端项目启动指南

## 快速开始

### 方式一：使用启动脚本（推荐）

```bash
# 启动服务
./start.sh

# 停止服务
./stop.sh

# 重启服务
./restart.sh
```

### 方式二：手动启动

```bash
# 1. 安装依赖
npm install

# 2. 启动开发服务器
npm run dev
```

## 启动脚本功能

`start.sh` 脚本包含完整的启动流程：

1. **环境检查**
   - 检查 Node.js 版本（>= 16.0.0）
   - 检查 npm 版本（>= 8.0.0）
   - 检查 package.json 文件

2. **依赖管理**
   - 自动检查 node_modules 是否存在
   - 如果不存在，自动安装依赖
   - 可选：检查并更新依赖

3. **端口检查**
   - 检查端口 3000 是否被占用
   - 如果被占用，尝试停止之前的进程

4. **后端服务检查**
   - 检查后端服务（supervisor-agent）是否可访问
   - 默认地址：http://localhost:10008

5. **启动开发服务器**
   - 后台启动 Vite 开发服务器
   - 保存进程 ID 到 `.pid` 文件
   - 日志输出到 `nohup.out`

## 配置说明

### 端口配置

默认端口：`3000`

如需修改端口，可以：
1. 编辑 `vite.config.ts` 中的 `server.port`
2. 或使用环境变量：`PORT=3001 ./start.sh`

### 后端 API 配置

默认后端地址：`http://localhost:10008`

可以在前端设置页面修改，或编辑 `src/stores/config.ts`

## 常用命令

```bash
# 开发模式启动
npm run dev

# 构建生产版本
npm run build

# 预览生产构建
npm run preview

# 代码检查
npm run lint

# 代码格式化
npm run format

# 类型检查
npm run type-check
```

## 访问地址

启动成功后，访问：
- **前端界面**: http://localhost:3000
- **后端 API**: http://localhost:10008（默认）

## 故障排除

### 端口被占用

```bash
# 查看占用端口的进程
lsof -i :3000

# 停止占用端口的进程
kill <PID>
```

### 依赖安装失败

```bash
# 清理缓存后重新安装
rm -rf node_modules package-lock.json
npm install
```

### 后端服务连接失败

确保后端服务（supervisor-agent）已启动：
```bash
# 检查后端服务是否运行
curl http://localhost:10008/health
```

### 查看日志

```bash
# 实时查看日志
tail -f nohup.out

# 查看最近的日志
tail -n 100 nohup.out
```

## 文件说明

- `start.sh` - 启动脚本（包含完整流程）
- `stop.sh` - 停止脚本
- `restart.sh` - 重启脚本
- `.pid` - 进程 ID 文件（自动生成）
- `nohup.out` - 日志文件（自动生成）

## 环境要求

- Node.js >= 16.0.0（推荐 20+）
- npm >= 8.0.0

