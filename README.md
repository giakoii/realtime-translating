# Realtime Translator

Dịch hội thoại Nhật ↔ Việt qua microphone, hiển thị phụ đề, tóm tắt cuộc họp và lưu vào PostgreSQL. Backend Spring Boot chạy trên JDK 25; frontend React chạy trong Chrome hoặc Edge.

## Chạy trên máy local

Yêu cầu: JDK 25, Node.js/npm và PostgreSQL 16 (hoặc Docker Desktop).

```powershell
docker compose up -d

$env:JAVA_HOME = 'C:\Users\KhoiPG\.jdks\openjdk-25'
$env:OPENAI_API_KEY = 'your-key'
cd backend
.\mvnw.cmd spring-boot:run
```

Mở terminal khác:

```powershell
cd frontend
npm ci
npm run dev
```

Mở `http://localhost:5173`, cho phép microphone, rồi bắt đầu phiên. Nút tạm dừng ngừng thu âm; nút tóm tắt yêu cầu tổng hợp nội dung; nút kết thúc lưu các câu còn lại. Backend mặc định ở `http://localhost:8080`; frontend dùng WebSocket `/ws/audio` cho audio và kết quả.

Nếu dùng PostgreSQL có sẵn, cấu hình `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Các giá trị mặc định là `jdbc:postgresql://localhost:5432/realtime_translator`, `postgres`, `postgres`. Flyway tự tạo schema khi khởi động. Trên Linux/macOS dùng `./mvnw` thay cho `mvnw.cmd` và đặt `JAVA_HOME` theo máy.

## Cấu hình

Các khóa `app.*` nằm trong `backend/src/main/resources/application.yaml`: Whisper, GPT translation, summary/title, silence, buffer, session queue, audio và WebSocket. `OPENAI_API_KEY` chỉ lấy từ môi trường; `OPENAI_BASE_URL` cho phép trỏ đến provider giả trong test. Frontend có cấu hình kết nối trong `frontend/.env.example`.

PCM đầu vào là mono 16 kHz, signed 16 bit little endian, base64 trong JSON. Session ID dài 8–64 ký tự chữ/số/`_`/`-`; sequence tăng dần trên mỗi kết nối. Chunk được xử lý tuần tự theo session. Nếu dịch thất bại, bản gốc vẫn được giữ. Tóm tắt cần ít nhất 3 câu và được kích hoạt thủ công hoặc sau 30 giây không nhận audio mới.

## Kiểm tra

```powershell
cd backend
.\mvnw.cmd verify

cd ..\frontend
npm ci
npm test
npm run build
```

Test mặc định sử dụng OpenAI giả, không gọi API trả phí. Các bài integration PostgreSQL dùng Docker hoặc một database thử nghiệm riêng qua `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME`, `TEST_DATABASE_PASSWORD`. Chỉ trỏ các biến này đến database dành riêng cho test vì các bài kiểm tra tạo và xóa dữ liệu. Nếu cả Docker và URL thử nghiệm đều không có, các bài DB được đánh dấu skip rõ ràng.

```powershell
$env:TEST_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/realtime_translator_test'
$env:TEST_DATABASE_USERNAME = 'postgres'
$env:TEST_DATABASE_PASSWORD = 'postgres'
cd backend
.\mvnw.cmd verify
```

Các bài WebSocket integration chạy backend thật với OpenAI giả và kiểm tra các dòng DB sau khi đóng session. Xem `frontend/README.md` cho kiểm thử trình duyệt và cấu hình E2E.

Để chạy E2E trình duyệt với backend thật nhưng không gọi OpenAI, khởi động test harness trong terminal riêng. Harness chạy WebSocket ở port 8081 và endpoint kiểm tra persistence chỉ bind loopback ở port 8082:

```powershell
$env:JAVA_HOME = 'C:\Users\KhoiPG\.jdks\openjdk-25'
$env:TEST_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/realtime_translator_test'
$env:TEST_DATABASE_USERNAME = 'postgres'
$env:TEST_DATABASE_PASSWORD = 'postgres'
cd backend
.\scripts\start-full-stack-test-server.ps1
```

Sau khi thấy `FULL_STACK_READY`, chạy ở terminal khác:

```powershell
cd frontend
$env:E2E_BACKEND_WS_URL = 'ws://127.0.0.1:8081/ws/audio'
$env:E2E_VERIFY_URL = 'http://127.0.0.1:8082/verify'
npm run e2e:full
```

`backend/scripts/verify-db-outage-recovery.ps1` kiểm tra mất DB giữa phiên và phục hồi không mất/trùng message. Script này chủ động stop/start PostgreSQL bằng `pg_ctl`, vì vậy chỉ dùng với cluster test tạm, độc lập, nằm dưới thư mục temp; không trỏ đến database dùng chung hoặc database chứa dữ liệu cần giữ. Khi harness đang chạy với cluster đó:

```powershell
.\scripts\verify-db-outage-recovery.ps1 -PostgresRoot "$env:TEMP\my-isolated-pg16"
```

Kết quả đạt phải có một meeting, đúng bốn message theo thứ tự, `endedAt` và title; script luôn thử khởi động lại PostgreSQL trong `finally` nếu phép thử gặp lỗi.

## Phạm vi hiện tại

Ứng dụng chạy một backend instance; transcript được giữ trong RAM suốt phiên và ghi vào DB theo batch. Mất kết nối kết thúc meeting, kết nối lại bắt đầu session mới. Một lần ghi DB thất bại giữ buffer để retry trong tiến trình đang chạy; restart tiến trình có thể mất dữ liệu chưa flush. Dữ liệu đã ghi được giữ trong volume PostgreSQL.

Frontend web chưa có Tauri overlay, export/history, TTS, đăng nhập hay nhiều người nói. Dùng HTTPS/WSS khi triển khai ngoài localhost và cấu hình `app.websocket.allowed-origins` phù hợp. Log transcript mặc định tắt; profile `local` có SQL parameter tracing phục vụ debug nên chỉ bật với dữ liệu thử nghiệm.
