# Review — NIH ODS exercise and athletic performance

## Kết quả và giới hạn

Đã xử lý toàn bộ file 539 dòng: giữ raw nguyên byte, phân 32 phần tiếng Anh không chồng/lỗ hổng;31 bài tiếng Việt theo chủ đề;21 hàng bảng structured;30 claim;24 numeric context chọn lọc;42 evaluation candidate. Không nhận bản tiếng Việt dịch từng câu/mọi số liệu. Chưa chunk/embedding/retrieval, chưa cập nhật code, DB, goal hoặc kế hoạch.

Đã kiểm tra cấu trúc tự động: source bytes/hash, line coverage, số bài/bảng, JSON/JSONL, ID tham chiếu và link raw, DRAFT / retrieval_enabled=false. ZIP được kiểm tra toàn vẹn. Không phải kiểm định y khoa, không điểmPASS chất lượng RAG.

## Đối chiếu trang nguồn

Đã mở trang ODS chính thức, xác định title/nhà xuất bản, updateApril 1,2024, disclaimer và sitepolicy. Không chạy diff toàn bộ nội dung web so với input và không xác nhận exactedition. Tệp thiếu title/date/bibliography/Disclaimer, nên metadata bổ sung được ghi provenance riêng. Bản web có bibliography; bộ này không khôi phục 219 records hoặc nhận đã đọc 219 nghiên cứu.

URL: https://ods.od.nih.gov/factsheets/ExerciseAndAthleticPerformance-HealthProfessional/
Policy: https://ods.od.nih.gov/About/Site_Policies.aspx
Ngày đối chiếu:2026-10-07. Không coi tuổi nguồn/lastupdate là bằng chứng mọi liều, luật hoặc danh sáchdoping còn hiện hành.

## Các vấn đề còn mở

| ID | Dòng raw | Điểm | Xử lý |
| --- | --- | --- | --- |
| ODS-Q01 | 262 | Caffeine: ví dụ quy đổi không khớp: 2–6 mg/kg với70 kg tương ứng140–420 mg bằng phép tính; nguồn lại ghi210–420 mg. | Không sửa ngầm; cách ly ví dụ sai khỏi chunk trả lời và calculator. |
| ODS-Q02 | 179 | Beetroot: nitric acid/nitric oxide: Nguồn nói chuyển thành nitric oxide rồi gọi nitric acid là chất giãn mạch. | Giữ nguyên nguồn; không dùng acid nitric làm giải thích/khuyến nghị; reviewer kiểm tra thuật ngữ. |
| ODS-Q03 | 233 | Betaine: nitric acid: Thân nguồn dùng nitric acid, bảng dùng nitric oxide. | Giữ cờ chờ rà soát, không khẳng định cơ chế bị nghi vấn. |
| ODS-Q04 | 416 | Protein thực vật: diễn đạt “lacks”: Nguồn nói soy lacks methionine và rice lacks isoleucine; dễ bị diễn giải thành không có amino acid đó. | Không đưa câu này vào claim kiến thức đã duyệt; cần chuyên gia và nguồn nguyên thủy để phân biệt thiếu tương đối/giới hạn và vắng mặt. |
| ODS-Q05 | 456 | Bicarbonate: câu mâu thuẫn: Câu “generally much less...” theo lượng300 mg/kg có vẻ mâu thuẫn với phần efficacy dùng cùng lượng. | Chưa kết luận intended wording; không sửa ngầm, không dùng làm dosing rule. |
| ODS-Q06 | 280 | Caffeine: quy tắc thi đấu có thời điểm: Nguồn nêu IOC12 và NCAA15 mcg/ml cùng WADA không cấm; không đủ xác nhận hiệu lực hiện tại. | Không áp dụng hiện hành; cần danh sách/chính sách chính thức theo tổ chức, năm, loại thi đấu. |
| ODS-Q07 | 83 | Deer antler: bảng dễ gộp tác dụng phụ/cấm: Bảng liệt kê adverse effects gắn prescription IGF-1 và banned; thân bài giải thích rõ hơn. | Giữ context: không gán tác dụng phụ của thuốc IGF-1 cho mọi nhung hươu; không gộp mọi sản phẩm thành một banned ingredient. |
| ODS-Q08 | 389 | Iron: UL so với độc tính cấp: UL45 mg/ngày tuổi≥14 khác liều cấp tính>20 mg/kg; liều điều trị khác nhu cầu/RDA. | Không đánh đồng các ngưỡng, không gợi ý liều điều trị hoặc tự bù sắt. |
| ODS-Q09 | 486 | Banned substances/regulation: phạm vi Mỹ và thời điểm: Mô tả FDA/DSHEA, năm2004/2013 và anti-doping không phải pháp luật Việt Nam hay chứng nhận năm2026. | Giữ historical/source context; cần xác minh riêng trước tư vấn pháp lý/quy định hiện hành. |
| ODS-Q10 | 539 | Thiếu bibliography, title và disclaimer trong tệp: Tệp có các chỉ số1–219 nhưng không có danh mục References; cũng không có tiêu đề trang/Updated/Disclaimer. | Khôi phục metadata từ trang chính thức riêng; đánh dấu bibliography_missing, không tạo219 tài liệu hoặc nói đã đọc chúng. |
| ODS-Q11 | 271 | Safety caffeine400–500: khác nguồn và nhóm: Bảng gom400–500, thân bài nêu FDA400 và AMA500; adolescents/energy drinks có bối cảnh khác. | Không gộp thành một UL chung hoặc an toàn cá nhân; không áp cho thai kỳ/bệnh lý/thiếu niên. |
| ODS-Q12 | 140 | Bảng thành phần đơn khác blend: Chú thích bảng nêu evidence cho từng thành phần, phối hợp có thể khác. | Giữ cùng bảng/dữ liệu; không suy ra pre-workout blend an toàn/hiệu quả từ từng thành phần. |

## Kiểm tra cách diễn giải

Giữ proposed mechanism khác benefit; EAA/BCAA, creatine/creatinine, citrulline/malate, HMB-Ca/FA, Ephedra/ephedrine, Panax/Eleutherococcus và thuốcIGF-1/nhung hươu khác nhau. Không ghép kết quả thành phần đơn để tuyên bố blend hiệu quả/an toàn. Không suy từ “none known” thành an toàn vô hạn.

Giữ nguồn/tổ chức của ý kiến trái nhau: beta-alanine/HMB/timingprotein, FDA 400 so vớiAMA 500 caffeine. Giữ RDA/UL/dose trial/dose opinion, mg/g, kgbodyweight/kgleanmass, serving/day và phần trăm năng lượng riêng. Không lấy liều trong thử nghiệm thất bại hoặc bệnh nhân để chỉ định người tập. Không đổi sốODSprotein 1,2–2,0 thànhISSN 1,4–2,0 để ép khớp.

Các bảng/tài liệu có số gây nguy cơ hiểu sai đang bị tắtretrieval; các câu disputed không thànhclaim khoa học đã duyệt. Thông tin quản lý HoaKỳ/chốngdoping giữ ở historical role, không trả lời luậtViệtNam hoặc hiệu lựcgiảiđấu hiện hành từ snapshot này.

## Còn phải làm trước phát hành

Reviewer chuyên môn và reviewer tiếng Việt cần duyệt; giải quyết 12 issue bằng nguồn nguyên thủy/nguồn chính thức phù hợp và ghi quyết định. Các thông tin luật/chốngdoping cần kiểm tra hiệu lực theo tổ chức/quốc gia/năm. Bibliography còn thiếu nếu dự án cần citation cấp nghiên cứu; citation hiện tại truy về fact sheet/snapshot mục/dòng.

Dự án cần duyệt policy F 01, schema metadata và luồng Content/Knowledge; duyệt từng version rồiACTIVE. Sau đó chunk không rơi bối cảnh, embed và đánh giá retrieval/answer/citation. Bộ 42 case là danh sách đề xuất, không có kết quả measured hay reviewer. Chưa đo recall, accuracy, latency hoặc cost.

Attribution/điều kiện của bản tiếng Việt đã diễn giải cần được kiểm tra trước công bố, không nhận bản dịch chính thức hoặc ODSphêduyệt. Giữ riêng điều kiệnWHO/ISSN/NIDDK/ODS; không sao chép giấy phép của nguồn khác.
