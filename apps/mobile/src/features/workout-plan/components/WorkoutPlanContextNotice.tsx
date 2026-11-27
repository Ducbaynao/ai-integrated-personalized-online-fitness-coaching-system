import React, { useState } from 'react';
import { Text, useColorScheme } from 'react-native';
import { useRouter } from 'expo-router';
import { AppCard } from '@/design-system/components/AppCard';
import { AppButton } from '@/design-system/components/AppButton';
import { coachingErrorMessage } from '@/features/coaching/coachingMessages';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { typography } from '@/design-system/tokens/typography';
import { WorkoutPlanContext } from '../useWorkoutPlanContext';

export function WorkoutPlanContextNotice({ context, hasDraft = false }: { context: WorkoutPlanContext; hasDraft?: boolean }) {
  const router = useRouter();
  const [confirm, setConfirm] = useState(false);
  const theme = getSemanticColors(useColorScheme() === 'dark');
  if (context.ready) return null;
  const busy = context.status === 'loading' || context.status === 'setting-up';
  const title = busy ? context.status === 'setting-up' ? 'Đang thiết lập Tự tập' : 'Đang kiểm tra chế độ tập'
    : context.status === 'error' ? 'Chưa thể kiểm tra chế độ tập'
    : context.status === 'human' ? 'Giáo án do huấn luyện viên quyết định'
    : context.status === 'relationship' ? 'Cần kiểm tra quan hệ huấn luyện'
    : context.status === 'future' ? 'Kỳ huấn luyện chưa bắt đầu'
    : context.status === 'ended' ? 'Kỳ huấn luyện trước đã kết thúc'
    : context.status === 'invalid' ? 'Cần tải lại chế độ tập' : 'Chọn chế độ trước khi tạo kế hoạch';
  const message = context.status === 'error' ? coachingErrorMessage(context.error)
    : context.status === 'human' ? 'Trong chế độ Có huấn luyện viên, PT phụ trách quyết định giáo án theo quyền được chia sẻ. Hãy mở mục Huấn luyện để trao đổi với PT.'
    : context.status === 'relationship' ? 'Bạn vẫn có quan hệ huấn luyện nhưng chưa có kỳ phù hợp. Hãy kiểm tra quan hệ và quyền với PT trước khi tiếp tục.'
    : context.status === 'future' ? 'Bạn đã có kỳ huấn luyện trong tương lai. Hãy kiểm tra lịch hiệu lực trong mục Huấn luyện.'
    : busy ? 'Chỉ tiếp tục khi đã đọc được chế độ và quyền hiện tại.'
    : context.status === 'invalid' ? 'Chưa xác nhận được kỳ có hiệu lực. Hãy kiểm tra lại kết nối và thời gian trên thiết bị.'
    : 'Chọn Tự tập để tự quyết định kế hoạch, hoặc kết nối với huấn luyện viên trong mục Huấn luyện. Coaching Period ghi nhận quyền tập luyện, không phải hợp đồng hay khóa học.';
  return <AppCard testID="workout-plan-context-notice" title={title} message={message} state={busy ? 'loading' : context.status === 'error' ? 'error' : 'info'}>
    {hasDraft ? <Text accessibilityRole="alert" style={[typography.bodySmall, { color: theme.textPrimary }]}>Thông tin kế hoạch chưa lưu được giữ trong màn hình này. Sau khi thiết lập, quay lại và kiểm tra chế độ để tiếp tục.</Text> : null}
    {context.canSetup && !confirm ? <AppButton testID="choose-self-directed" label="Chọn Tự tập" onPress={() => setConfirm(true)} /> : null}
    {context.canSetup && confirm ? <>
      <Text style={[typography.bodySmall, { color: theme.textPrimary }]}>Bạn xác nhận tự quyết định giáo án từ bây giờ. Mục tiêu, kế hoạch và lịch sử tập luyện được giữ nguyên.</Text>
      <AppButton testID="confirm-self-directed" label="Xác nhận Tự tập" onPress={() => { setConfirm(false); void context.setup(); }} />
      <AppButton label="Để sau" variant="secondary" onPress={() => setConfirm(false)} />
    </> : null}
    {!busy ? <>
      <AppButton testID="open-context-coaching" label="Mở mục Huấn luyện" variant="secondary" onPress={() => router.push('/coaching' as never)} />
      <AppButton testID="refresh-workout-context" label="Kiểm tra lại chế độ" variant="secondary" onPress={() => { setConfirm(false); void context.refresh(); }} />
    </> : null}
  </AppCard>;
}
