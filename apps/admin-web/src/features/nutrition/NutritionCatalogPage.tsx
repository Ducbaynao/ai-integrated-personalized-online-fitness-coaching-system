import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../services/apiClient.ts'
import { nutritionCatalogApi, type CatalogFood, type RevisionInput } from '../../services/nutritionCatalogApi.ts'

type Command =
  | { kind: 'draft'; body: Parameters<typeof nutritionCatalogApi.draft>[0] }
  | { kind: 'revise'; id: string; body: Parameters<typeof nutritionCatalogApi.revise>[1] }
  | { kind: 'transition'; id: string; body: Parameters<typeof nutritionCatalogApi.transition>[1] }

export function NutritionCatalogPage() {
  const client = useQueryClient()
  const query = useQuery({ queryKey: ['admin-nutrition-catalog'], queryFn: nutritionCatalogApi.list, retry: false })
  const [selected, setSelected] = useState<CatalogFood | null>(null)
  const [fdcId, setFdcId] = useState('')
  const [release, setRelease] = useState('')
  const [sourceDate, setSourceDate] = useState('')
  const [name, setName] = useState('')
  const [recordVersion, setRecordVersion] = useState('')
  const [sourceUrl, setSourceUrl] = useState('')
  const [kcal, setKcal] = useState('')
  const [protein, setProtein] = useState('')
  const [carbs, setCarbs] = useState('')
  const [fat, setFat] = useState('')
  const [portionLabel, setPortionLabel] = useState('')
  const [gramsPerServing, setGramsPerServing] = useState('')
  const [gramsPerMl, setGramsPerMl] = useState('')
  const [factorSource, setFactorSource] = useState('')
  const [mlFactorSource, setMlFactorSource] = useState('')
  const [reason, setReason] = useState('')
  const [pending, setPending] = useState<Command | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')

  const load = (food: CatalogFood) => {
    setSelected(food); setFdcId(String(food.fdcId)); setRelease(food.sourceRelease)
    setSourceDate(food.sourceDate); setName(food.name); setRecordVersion(food.sourceRecordVersion)
    setSourceUrl(food.sourceUrl); setKcal(food.energyKcalPer100g?.toString() ?? '')
    setProtein(food.proteinGPer100g?.toString() ?? '')
    setCarbs(food.carbohydrateGPer100g?.toString() ?? '')
    setFat(food.fatGPer100g?.toString() ?? '')
    const portion = food.portions[0]
    setPortionLabel(portion?.label ?? ''); setGramsPerServing(portion?.gramsPerServing?.toString() ?? '')
    setGramsPerMl(portion?.gramsPerMl?.toString() ?? '')
    setFactorSource(portion?.factorSource ?? '')
    setMlFactorSource(portion?.mlFactorSource ?? '')
    setPending(null); setMessage('')
  }
  const numeric = (raw: string) => raw.trim() ? Number(raw) : null
  const validNutrient = (raw: string) => !raw.trim() || /^\d{1,12}(\.\d{1,6})?$/.test(raw)
  const validFactor = (raw: string) => !raw.trim() || /^\d{1,9}(\.\d{1,9})?$/.test(raw)
  const revision = (): RevisionInput | null => {
    if (!name.trim() || !recordVersion.trim() || ![
      `https://fdc.nal.usda.gov/food-details/${fdcId}/nutrients`,
      `https://api.nal.usda.gov/fdc/v1/food/${fdcId}`,
    ].includes(sourceUrl.trim())
        || ![kcal, protein, carbs, fat].every(validNutrient)
        || ![gramsPerServing, gramsPerMl].every(validFactor)
        || portionLabel.trim() && gramsPerServing && !factorSource.trim()
        || portionLabel.trim() && gramsPerMl && !mlFactorSource.trim()) return null
    return { name: name.trim(), sourceRecordVersion: recordVersion.trim(), sourceUrl: sourceUrl.trim(),
      energyKcalPer100g: numeric(kcal), proteinGPer100g: numeric(protein),
      carbohydrateGPer100g: numeric(carbs), fatGPer100g: numeric(fat),
      portions: portionLabel.trim() ? [{ label: portionLabel.trim(), gramsPerServing: numeric(gramsPerServing),
        gramsPerMl: numeric(gramsPerMl), factorSource: factorSource.trim() || null,
        mlFactorSource: mlFactorSource.trim() || null }] : [] }
  }
  const execute = async (command: Command) => {
    if (busy) return
    setBusy(true); setPending(command); setMessage('')
    try {
      const result = command.kind === 'draft' ? await nutritionCatalogApi.draft(command.body)
        : command.kind === 'revise' ? await nutritionCatalogApi.revise(command.id, command.body)
          : await nutritionCatalogApi.transition(command.id, command.body)
      load(result); setPending(null); setMessage('Đã lưu và ghi audit. Lịch sử Food Log không thay đổi.')
      await client.invalidateQueries({ queryKey: ['admin-nutrition-catalog'] })
    } catch (error) {
      if (error instanceof ApiError && [403, 404].includes(error.status)) {
        setSelected(null); setPending(null); client.removeQueries({ queryKey: ['admin-nutrition-catalog'] })
        setMessage('Không còn quyền quản lý catalog này.')
      } else if (error instanceof ApiError && error.status === 409) {
        setPending(null); await client.invalidateQueries({ queryKey: ['admin-nutrition-catalog'] })
        setMessage('Nguồn hoặc phiên bản đã đổi. Hãy tải lại và kiểm tra trước khi quyết định mới.')
      } else if (error instanceof ApiError && error.status === 400) {
        setPending(null); setMessage('Dữ liệu chưa hợp lệ; kiểm tra nguồn, nutrient và hệ số quy đổi.')
      } else setMessage('Chưa rõ lệnh đã lưu chưa. Chỉ thử lại cùng nội dung và mã lệnh.')
    } finally { setBusy(false) }
  }
  const save = () => {
    if (selected && selected.portions.length > 1) {
      setMessage('Bản này có nhiều portion; biểu mẫu một portion không thể giữ đầy đủ provenance. Không tạo revision từ biểu mẫu này.'); return
    }
    const input = revision()
    if (!input || !/^\d+$/.test(fdcId) || !/^\d{4}-\d{2}-\d{2}$/.test(sourceDate) || !release.trim()) {
      setMessage('Cần FDC ID, bản phát hành/ngày nguồn và nutrient/portion có dẫn nguồn hợp lệ.'); return
    }
    if (selected?.status === 'PUBLISHED' && !window.confirm(
      'Revision mới sẽ có hiệu lực ngay cho lần ghi catalog tiếp theo. Food Log cũ giữ snapshot. Tiếp tục?',
    )) return
    const commandKey = crypto.randomUUID()
    if (selected) void execute({ kind: 'revise', id: selected.id,
      body: { expectedVersion: selected.aggregateVersion, revision: input, commandKey } })
    else void execute({ kind: 'draft', body: { fdcId: Number(fdcId), sourceRelease: release.trim(),
      sourceDate, revision: input, commandKey } })
  }
  const transition = (action: 'PUBLISH' | 'RETIRE') => {
    if (!selected || !reason.trim() || !window.confirm(action === 'PUBLISH'
      ? `Công bố ${selected.name} từ USDA Foundation ${selected.sourceRelease}?`
      : `Ngừng dùng ${selected.name}? Log cũ vẫn giữ nguyên.`)) return
    void execute({ kind: 'transition', id: selected.id, body: { action,
      expectedVersion: selected.aggregateVersion, reason: reason.trim(), commandKey: crypto.randomUUID() } })
  }
  const field = (label: string, value: string, update: (next: string) => void, locked = false) =>
    <label>{label}<input value={value} onChange={(event) => update(event.target.value)} disabled={busy || locked} /></label>

  return <section className="page-stack">
    <h1>Catalog dinh dưỡng có nguồn</h1>
    <p>Chỉ duyệt tập nhỏ USDA Foundation Foods. Món riêng của học viên không tự trở thành catalog. Thiếu dữ liệu không phải 0.</p>
    {message ? <p role="alert">{message}</p> : null}
    {pending && !busy ? <button onClick={() => void execute(pending)}>Thử lại cùng lệnh</button> : null}
    {query.isPending ? <p>Đang tải catalog…</p> : null}
    {query.isError ? <button onClick={() => void query.refetch()}>Tải lại catalog</button> : null}
    {query.data?.length === 0 ? <p>Chưa có thực phẩm catalog.</p> : null}
    <div className="surface">
      {query.data?.map((food) => <button key={food.id} onClick={() => load(food)}>
        {food.name} · FDC {food.fdcId} · {food.status} · v{food.aggregateVersion}
      </button>)}
    </div>
    <div className="surface page-stack">
      <h2>{selected ? `Revision mới cho ${selected.name}` : 'Chọn food USDA vào bản nháp'}</h2>
      {selected ? <button onClick={() => { setSelected(null); setPending(null); setMessage('') }}>Tạo food khác</button> : null}
      {field('FDC ID', fdcId, setFdcId, Boolean(selected))}
      {field('Bản phát hành nguồn', release, setRelease, Boolean(selected))}
      {field('Ngày nguồn YYYY-MM-DD', sourceDate, setSourceDate, Boolean(selected))}
      {field('Tên nguồn', name, setName)}
      {field('Version bản ghi nguồn', recordVersion, setRecordVersion)}
      {field('URL bản ghi FoodData Central', sourceUrl, setSourceUrl)}
      {field('Kcal trên 100 g; để trống nếu chưa biết', kcal, setKcal)}
      {field('Protein g trên 100 g; để trống nếu chưa biết', protein, setProtein)}
      {field('Carbohydrate g trên 100 g; để trống nếu chưa biết', carbs, setCarbs)}
      {field('Fat g trên 100 g; để trống nếu chưa biết', fat, setFat)}
      <h3>Bằng chứng một portion (tùy chọn)</h3>
      {field('Tên portion', portionLabel, setPortionLabel)}
      {field('Gram mỗi serving đã duyệt', gramsPerServing, setGramsPerServing)}
      {field('Gram mỗi ml có bằng chứng riêng', gramsPerMl, setGramsPerMl)}
      {field('Nguồn hệ số', factorSource, setFactorSource)}
      {field('Nguồn riêng cho gram/ml', mlFactorSource, setMlFactorSource)}
      <button disabled={busy || selected?.status === 'RETIRED'} onClick={save}>{selected ? 'Tạo revision có audit' : 'Tạo bản nháp'}</button>
      {selected ? <>
        {field('Lý do publish/retire', reason, setReason)}
        {selected.status === 'DRAFT' ? <button disabled={busy || !reason.trim()} onClick={() => transition('PUBLISH')}>Công bố</button> : null}
        {selected.status === 'PUBLISHED' ? <button disabled={busy || !reason.trim()} onClick={() => transition('RETIRE')}>Ngừng dùng</button> : null}
      </> : null}
    </div>
  </section>
}
