package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// OCRResult 接收的 OCR 结果
type OCRResult struct {
	Text     string `json:"text"`
	Device   string `json:"device,omitempty"`
	Time     string `json:"time,omitempty"`
	Filename string `json:"filename,omitempty"`
}

func main() {
	port := "8080"
	if len(os.Args) > 1 {
		port = os.Args[1]
	}

	// 创建输出目录
	outDir := "ocr_results"
	if err := os.MkdirAll(outDir, 0755); err != nil {
		log.Fatalf("创建输出目录失败: %v", err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", handleHealth)
	mux.HandleFunc("/upload", handleUpload(outDir))
	mux.HandleFunc("/", handleIndex)

	// 获取本机 IP 地址
	ips := getLocalIPs()

	log.Println("========================================")
	log.Println("  OCR 结果接收服务器已启动")
	log.Println("========================================")
	log.Printf(" 端口: %s\n", port)
	log.Printf(" 输出目录: %s\n", outDir)
	log.Println(" 本机 IP 地址:")
	for _, ip := range ips {
		log.Printf("   http://%s:%s\n", ip, port)
	}
	log.Println("----------------------------------------")
	log.Printf(" 手机端填写地址: http://%s:%s/upload\n", ips[0], port)
	log.Println(" 测试: curl -X POST http://localhost:"+port+"/upload -H 'Content-Type: application/json' -d '{\"text\":\"你好世界\"}'")
	log.Println("========================================")

	server := &http.Server{
		Addr:         ":" + port,
		Handler:      corsMiddleware(mux),
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 10 * time.Second,
	}

	if err := server.ListenAndServe(); err != nil {
		log.Fatalf("服务器启动失败: %v", err)
	}
}

// getLocalIPs 获取本机局域网 IP 地址列表
func getLocalIPs() []string {
	var ips []string
	interfaces, err := net.Interfaces()
	if err != nil {
		log.Printf("获取网络接口失败: %v", err)
		return []string{"127.0.0.1"}
	}

	for _, iface := range interfaces {
		// 跳过回环接口和未启用的接口
		if iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		if iface.Flags&net.FlagUp == 0 {
			continue
		}

		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}

		for _, addr := range addrs {
			ipnet, ok := addr.(*net.IPNet)
			if !ok || ipnet.IP.IsLoopback() {
				continue
			}
			// 只取 IPv4 地址
			if ipnet.IP.To4() != nil {
				ips = append(ips, ipnet.IP.String())
			}
		}
	}

	if len(ips) == 0 {
		ips = []string{"127.0.0.1"}
	}
	return ips
}

// handleHealth 健康检查
func handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":    "ok",
		"time":      time.Now().Format(time.RFC3339),
		"version":   "1.0",
		"endpoints": []string{"/health", "/upload"},
	})
}

// handleUpload 处理 OCR 结果上传
func handleUpload(outDir string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "仅支持 POST 请求", http.StatusMethodNotAllowed)
			return
		}

		var result OCRResult
		contentType := r.Header.Get("Content-Type")

		switch {
		case strings.Contains(contentType, "application/json"):
			// JSON 格式
			if err := json.NewDecoder(r.Body).Decode(&result); err != nil {
				http.Error(w, fmt.Sprintf("JSON 解析失败: %v", err), http.StatusBadRequest)
				return
			}

		case strings.Contains(contentType, "text/plain"):
			// 纯文本格式
			body, err := io.ReadAll(r.Body)
			if err != nil {
				http.Error(w, fmt.Sprintf("读取 body 失败: %v", err), http.StatusBadRequest)
				return
			}
			result.Text = string(body)
			result.Device = r.Header.Get("X-Device")

		default:
			// 自动检测：尝试 JSON，否则当纯文本
			body, err := io.ReadAll(r.Body)
			if err != nil {
				http.Error(w, fmt.Sprintf("读取 body 失败: %v", err), http.StatusBadRequest)
				return
			}
			if json.Unmarshal(body, &result) != nil {
				result.Text = string(body)
				result.Device = r.Header.Get("X-Device")
			}
		}

		result.Text = strings.TrimSpace(result.Text)
		if result.Text == "" {
			http.Error(w, "文本内容为空", http.StatusBadRequest)
			return
		}

		// 记录时间
		now := time.Now()
		if result.Time == "" {
			result.Time = now.Format("2006-01-02 15:04:05")
		}

		// 生成文件名
		if result.Filename == "" {
			result.Filename = fmt.Sprintf("ocr_%s.txt", now.Format("20060102_150405"))
		}

		// 保存到文件
		filePath := filepath.Join(outDir, result.Filename)
		content := fmt.Sprintf("=== OCR 结果 ===\n")
		content += fmt.Sprintf("时间: %s\n", result.Time)
		content += fmt.Sprintf("设备: %s\n", result.Device)
		content += fmt.Sprintf("来源 IP: %s\n", r.RemoteAddr)
		content += fmt.Sprintf("================\n\n")
		content += result.Text
		content += "\n\n"

		if err := os.WriteFile(filePath, []byte(content), 0644); err != nil {
			log.Printf("保存文件失败 %s: %v", filePath, err)
			http.Error(w, "保存失败", http.StatusInternalServerError)
			return
		}

		// 同时追加到聚合日志
		logFile := filepath.Join(outDir, "_all_results.log")
		logLine := fmt.Sprintf("[%s] [%s] %s\n", result.Time, result.Device, result.Text)
		f, err := os.OpenFile(logFile, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
		if err == nil {
			f.WriteString(logLine)
			f.Close()
		}

		// 控制台输出
		log.Printf("收到 OCR 结果:")
		log.Printf("  时间: %s", result.Time)
		log.Printf("  设备: %s", result.Device)
		log.Printf("  文件: %s", filePath)
		log.Printf("  内容: %s", truncateText(result.Text, 200))

		// 响应
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":   "ok",
			"message":  "接收成功",
			"filename": result.Filename,
			"length":   len(result.Text),
		})
	}
}

// handleIndex 首页
func handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>OCR 接收服务</title>
  <style>
    body { font-family: system-ui, -apple-system, sans-serif; max-width: 600px; margin: 40px auto; padding: 0 20px; line-height: 1.6; }
    h1 { color: #1976d2; }
    .endpoint { background: #f5f5f5; padding: 12px; border-radius: 8px; margin: 8px 0; }
    code { background: #e0e0e0; padding: 2px 6px; border-radius: 4px; font-size: 14px; }
  </style>
</head>
<body>
  <h1>📷 OCR 接收服务</h1>
  <p>服务正在运行，等待手机端上传 OCR 结果...</p>
  <div class="endpoint">
    <strong>POST</strong> <code>/upload</code> — 上传 OCR 结果
  </div>
  <div class="endpoint">
    <strong>GET</strong> <code>/health</code> — 健康检查
  </div>
  <p>结果保存在 <code>ocr_results/</code> 目录中</p>
</body>
</html>`)
}

// corsMiddleware 添加 CORS 头
func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-Device")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}

		next.ServeHTTP(w, r)
	})
}

func truncateText(s string, n int) string {
	runes := []rune(s)
	if len(runes) <= n {
		return s
	}
	return string(runes[:n]) + "..."
}
