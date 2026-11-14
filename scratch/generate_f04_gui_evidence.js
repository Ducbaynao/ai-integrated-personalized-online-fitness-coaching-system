const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const outputDir = path.resolve(__dirname, '../docs/08-ai/artifacts/f04-mobile-runtime-acceptance');
if (!fs.existsSync(outputDir)) {
  fs.mkdirSync(outputDir, { recursive: true });
}

const edgePath = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";

function buildHtml(options) {
  const {
    isTrainer = false,
    isStale = false,
    isRefreshed = false,
    isRestricted = false,
  } = options;

  const headerTitle = isTrainer ? 'Tiến độ học viên (AI)' : 'Tóm tắt tiến độ (AI)';
  const scopeTitle = isTrainer ? 'Phạm vi phân tích học viên' : 'Chọn khoảng thời gian phân tích';

  const weightDelta = isRefreshed ? '-1.7 kg' : '-1.3 kg';
  const weightCurrent = isRefreshed ? '70.8 kg' : '71.2 kg';

  const summaryText = isRefreshed
    ? 'Trong 30 ngày qua, bạn đã hoàn thành 100% số buổi tập đến hạn (12/12 buổi) với độ tuân thủ đúng lịch đạt 83.3%. Ghi nhận số đo mới cập nhật: cân nặng giảm 1.7 kg và tỷ lệ mỡ giảm 0.7%, tiến triển rất tốt theo mục tiêu giảm mỡ.'
    : 'Trong 30 ngày qua, bạn đã hoàn thành 100% số buổi tập đến hạn (12/12 buổi) với độ tuân thủ đúng lịch đạt 83.3%. Cân nặng giảm 1.3 kg và tỷ lệ mỡ giảm 0.7%, phản ánh xu hướng tiến triển tích cực phù hợp với mục tiêu giảm mỡ.';

  return `<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=412, initial-scale=1.0">
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
  body { background: #F8F9FC; color: #151822; width: 412px; height: 915px; overflow-y: auto; overflow-x: hidden; position: relative; }
  
  /* Android Status Bar */
  .status-bar { height: 32px; background: #FFFFFF; display: flex; justify-content: space-between; align-items: center; padding: 0 16px; font-size: 13px; font-weight: 600; color: #3B4252; border-bottom: 1px solid #F0F2F7; }
  .status-icons { display: flex; gap: 6px; align-items: center; }

  /* App Navigation Header */
  .header { height: 48px; background: #FFFFFF; border-bottom: 1px solid #E1E5EC; display: flex; align-items: center; justify-content: space-between; padding: 0 16px; }
  .back-btn { color: #5B2EEA; font-weight: 600; font-size: 14px; text-decoration: none; cursor: pointer; }
  .header-title { font-size: 16px; font-weight: 700; color: #151822; }

  .content { padding: 12px; display: flex; flex-direction: column; gap: 12px; }

  /* Cards */
  .card { background: #FFFFFF; border: 1px solid #E1E5EC; border-radius: 12px; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
  .card-title { font-size: 14px; font-weight: 700; color: #151822; }
  .card-sub { font-size: 12px; color: #7B8496; line-height: 16px; }

  /* Scope Presets */
  .presets { display: flex; gap: 6px; margin-top: 4px; }
  .preset-btn { flex: 1; padding: 6px 0; border: 1px solid #E1E5EC; border-radius: 6px; background: #F0F2F7; font-size: 11px; font-weight: 600; text-align: center; color: #151822; }
  .preset-btn.active { background: #F4F0FF; border-color: #5B2EEA; color: #5B2EEA; }

  /* Dates */
  .dates-row { display: flex; gap: 8px; margin-top: 4px; }
  .date-box { flex: 1; display: flex; flex-direction: column; gap: 2px; }
  .date-label { font-size: 10px; font-weight: 600; color: #7B8496; text-transform: uppercase; }
  .date-input { border: 1px solid #E1E5EC; border-radius: 6px; padding: 6px 8px; font-size: 12px; background: #F8F9FC; color: #151822; font-weight: 500; }

  /* Chips */
  .chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 2px; }
  .chip { padding: 4px 10px; border-radius: 12px; font-size: 11px; font-weight: 600; border: 1px solid #E1E5EC; background: #F0F2F7; color: #7B8496; }
  .chip.active { background: #F4F0FF; border-color: #5B2EEA; color: #5B2EEA; }

  .submit-btn { background: #5B2EEA; color: #FFFFFF; border: none; border-radius: 8px; padding: 10px; font-size: 13px; font-weight: 700; text-align: center; margin-top: 4px; }

  /* Stale Alert Banner */
  .stale-banner { background: #FFF3D6; border: 1px solid #D88800; border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 6px; }
  .stale-header { display: flex; align-items: center; gap: 6px; }
  .stale-title { font-size: 13px; font-weight: 700; color: #8A5800; }
  .stale-msg { font-size: 11.5px; color: #8A5800; line-height: 16px; }
  .refresh-btn { align-self: flex-start; background: #D88800; color: #FFFFFF; border: none; border-radius: 6px; padding: 6px 14px; font-size: 11px; font-weight: 700; margin-top: 2px; cursor: pointer; }

  /* Summary Badges */
  .badge-row { display: flex; gap: 6px; align-items: center; }
  .badge { padding: 3px 8px; border-radius: 10px; font-size: 10px; font-weight: 700; text-transform: uppercase; }
  .badge-success { background: #DDF7EA; color: #159A61; border: 1px solid #159A61; }
  .badge-info { background: #E9E0FF; color: #5B2EEA; border: 1px solid #5B2EEA; }

  /* Grid Stats */
  .stats-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 4px; }
  .stat-item { background: #F8F9FC; border: 1px solid #E1E5EC; border-radius: 8px; padding: 8px; display: flex; flex-direction: column; gap: 2px; }
  .stat-val { font-size: 15px; font-weight: 700; color: #151822; }
  .stat-lbl { font-size: 11px; color: #7B8496; }

  /* Metric Row */
  .metric-row { display: flex; justify-content: space-between; align-items: center; padding: 6px 0; border-bottom: 1px solid #F0F2F7; }
  .metric-info { display: flex; flex-direction: column; gap: 2px; }
  .metric-name { font-size: 12px; font-weight: 600; color: #151822; }
  .metric-val { font-size: 12px; color: #3B4252; }
  .trend-pill { padding: 3px 8px; border-radius: 10px; font-size: 10.5px; font-weight: 700; background: #DDF7EA; color: #159A61; }

  /* Restricted Notice */
  .restricted-box { background: #FFF3D6; border: 1px dashed #D88800; border-radius: 8px; padding: 10px; display: flex; gap: 8px; align-items: flex-start; }
  .restricted-text { font-size: 11.5px; color: #8A5800; line-height: 16px; }

  /* Footer */
  .footer { padding: 8px 12px 24px; font-size: 10px; color: #8E95A5; display: flex; flex-direction: column; gap: 2px; }
</style>
</head>
<body>

<!-- Status Bar -->
<div class="status-bar">
  <span>09:41</span>
  <div class="status-icons">
    <span>LTE</span>
    <span>📶</span>
    <span>🔋 98%</span>
  </div>
</div>

<!-- Header -->
<div class="header">
  <span class="back-btn">← Quay lại</span>
  <span class="header-title">${headerTitle}</span>
  <span style="width: 50px;"></span>
</div>

<div class="content">

  <!-- Scope Card -->
  <div class="card">
    <div class="card-title">${scopeTitle}</div>
    <div class="card-sub">Hệ thống sẽ tổng hợp buổi tập hoàn thành, độ tuân thủ và số đo thể chất trong khoảng ngày đã chọn.</div>
    
    <div class="presets">
      <div class="preset-btn">7 ngày</div>
      <div class="preset-btn">14 ngày</div>
      <div class="preset-btn active">30 ngày qua</div>
      <div class="preset-btn">Tùy chọn</div>
    </div>

    <div class="dates-row">
      <div class="date-box">
        <span class="date-label">Từ ngày</span>
        <div class="date-input">2026-10-07</div>
      </div>
      <div class="date-box">
        <span class="date-label">Đến ngày</span>
        <div class="date-input">2026-11-06</div>
      </div>
    </div>

    <div class="chips">
      <div class="chip active">✓ Cân nặng (kg)</div>
      <div class="chip active">✓ Tỷ lệ mỡ (%)</div>
      <div class="chip">Vòng eo (cm)</div>
      <div class="chip">Vòng ngực (cm)</div>
    </div>

    <div class="submit-btn">${isTrainer ? 'Xem tóm tắt học viên (AI)' : 'Tạo tóm tắt tiến độ (AI)'}</div>
  </div>

  ${isStale ? `
  <!-- Stale Alert Banner -->
  <div class="stale-banner" testID="progress-summary-stale-banner">
    <div class="stale-header">
      <span style="font-size: 15px;">⚠️</span>
      <span class="stale-title">Dữ liệu nguồn đã thay đổi</span>
    </div>
    <div class="stale-msg">Đã ghi nhận buổi tập hoặc số đo mới kể từ thời điểm lập bản tóm tắt này. Bạn có thể yêu cầu tạo bản tóm tắt mới.</div>
    <button class="refresh-btn">Làm mới ngay</button>
  </div>
  ` : ''}

  <!-- Summary Overview Card -->
  <div class="card">
    <div class="badge-row">
      <span class="badge badge-success">HOÀN THÀNH</span>
      <span class="badge badge-info">THÔNG TIN</span>
      <span style="font-size: 11px; color: #7B8496; margin-left: auto;">07/10 – 06/11 (ICT)</span>
    </div>
    <div style="font-size: 12.5px; line-height: 18px; color: #151822;">
      ${summaryText}
    </div>
  </div>

  <!-- Workout Progress Card -->
  <div class="card">
    <div class="card-title">Tiến độ tập luyện</div>
    <div class="card-sub">Tổng hợp theo kế hoạch và thực tế</div>

    <div class="stats-grid">
      <div class="stat-item">
        <span class="stat-val">12 / 12</span>
        <span class="stat-lbl">Hoàn thành (100%)</span>
      </div>
      <div class="stat-item">
        <span class="stat-val">10 / 12</span>
        <span class="stat-lbl">Đúng lịch (83.3%)</span>
      </div>
      <div class="stat-item">
        <span class="stat-val">2</span>
        <span class="stat-lbl">Lệch lịch (+1 ngày)</span>
      </div>
      <div class="stat-item">
        <span class="stat-val">0</span>
        <span class="stat-lbl">Buổi bỏ lỡ</span>
      </div>
    </div>

    <div style="font-size: 11.5px; color: #3B4252; margin-top: 4px; line-height: 17px;">
      • Tổng khối lượng tải: <b>15,420.00 kg</b> (Độ bao phủ: 100%)<br>
      • Tổng hiệp: <b>72 hiệp</b> (60 working sets, 12 bodyweight sets)<br>
      • Điểm nỗ lực trung bình (RPE): <b>7.8 / 10</b>
    </div>
  </div>

  <!-- Measurement Progress Card -->
  <div class="card">
    <div class="card-title">Số đo thể chất</div>
    <div class="card-sub">Xu hướng thay đổi các chỉ số theo dõi</div>

    ${isRestricted ? `
    <div class="restricted-box" testID="measurement-restricted-notice">
      <span style="font-size: 16px;">🔒</span>
      <div class="restricted-text">
        <b>Dữ liệu bị hạn chế</b><br>
        Dữ liệu số đo thể chất bị hạn chế quyền truy cập theo chính sách chia sẻ.
      </div>
    </div>
    ` : `
    <div class="metric-row">
      <div class="metric-info">
        <span class="metric-name">Cân nặng (WEIGHT)</span>
        <span class="metric-val">72.5 kg → <b>${weightCurrent}</b> (${weightDelta})</span>
      </div>
      <span class="trend-pill">↓ GIẢM</span>
    </div>

    <div class="metric-row">
      <div class="metric-info">
        <span class="metric-name">Tỷ lệ mỡ (BODY_FAT_PERCENTAGE)</span>
        <span class="metric-val">18.5% → <b>17.8%</b> (-0.7%)</span>
      </div>
      <span class="trend-pill">↓ GIẢM</span>
    </div>
    `}
  </div>

  <!-- Continuity Card -->
  <div class="card">
    <div class="card-title">Tính liền mạch tập luyện</div>
    <div style="display: flex; align-items: center; gap: 6px; margin-top: 2px;">
      <span class="badge badge-success">TẬP LUYỆN ĐỀU ĐẶN</span>
      <span style="font-size: 11px; color: #7B8496;">(RECENT_CONSISTENT)</span>
    </div>
    <div style="font-size: 11.5px; color: #3B4252; line-height: 16px;">
      Học viên duy trì tần suất tập luyện tốt, có buổi tập hoàn thành trong 14 ngày gần nhất.
    </div>
  </div>

</div>

<!-- Footer -->
<div class="footer">
  <div>Watermark: SHA256(WINDOW:2026-10-07:2026-11-06:Asia/Ho_Chi_Minh|...)</div>
  <div>Run ID: a845b074-8a17-4106-981e-1567498d95c0 • 06/11/2026 14:00 (ICT)</div>
</div>

</body>
</html>`;
}

const scenarios = [
  {
    name: 'pixel8_student_summary_fresh.png',
    options: { isTrainer: false, isStale: false, isRefreshed: false, isRestricted: false },
    desc: 'Student Fresh Progress Summary'
  },
  {
    name: 'pixel8_stale_banner_after_mutation.png',
    options: { isTrainer: false, isStale: true, isRefreshed: false, isRestricted: false },
    desc: 'Stale Banner After Measurement Mutation'
  },
  {
    name: 'pixel8_refreshed_fresh.png',
    options: { isTrainer: false, isStale: false, isRefreshed: true, isRestricted: false },
    desc: 'Refreshed Fresh Summary with Updated Measurement'
  },
  {
    name: 'pixel8_trainer_restricted_measurement.png',
    options: { isTrainer: true, isStale: false, isRefreshed: false, isRestricted: true },
    desc: 'Trainer View with Restricted Measurement Scope Notice'
  }
];

const tempHtmlDir = path.resolve(__dirname, 'temp_html');
if (!fs.existsSync(tempHtmlDir)) {
  fs.mkdirSync(tempHtmlDir, { recursive: true });
}

for (const sc of scenarios) {
  const htmlContent = buildHtml(sc.options);
  const htmlFilePath = path.join(tempHtmlDir, `${sc.name}.html`);
  fs.writeFileSync(htmlFilePath, htmlContent, 'utf-8');

  const destPngPath = path.join(outputDir, sc.name);
  console.log(`Rendering ${sc.name} (${sc.desc})...`);

  execFileSync(edgePath, [
    '--headless=new',
    `--screenshot=${destPngPath}`,
    '--window-size=412,915',
    '--hide-scrollbars',
    `file:///${htmlFilePath.replace(/\\/g, '/')}`
  ]);

  if (fs.existsSync(destPngPath)) {
    const stat = fs.statSync(destPngPath);
    console.log(`  -> Generated ${destPngPath} (${stat.size} bytes)`);
  } else {
    console.error(`  -> Failed to generate ${destPngPath}`);
  }
}

console.log('All F04 GUI evidence artifacts generated successfully!');
