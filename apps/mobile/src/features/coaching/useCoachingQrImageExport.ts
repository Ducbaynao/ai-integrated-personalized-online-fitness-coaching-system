import { File, Paths } from 'expo-file-system';
import * as MediaLibrary from 'expo-media-library';

export function useCoachingQrImageExport() {
  const [permission, requestPermission] = MediaLibrary.usePermissions({
    writeOnly: true,
    granularPermissions: ['photo'],
  });

  const saveImage = async (
    bytes: Uint8Array,
    filename: string,
    isCurrent: () => boolean
  ): Promise<'saved' | 'permission-denied' | 'cancelled'> => {
    if (!isCurrent()) return 'cancelled';
    let granted = permission?.granted ?? false;
    if (!granted) granted = (await requestPermission()).granted;
    if (!isCurrent()) return 'cancelled';
    if (!granted) return 'permission-denied';

    let tempFile: File | null = null;
    try {
      tempFile = new File(Paths.cache, filename);
      tempFile.write(bytes);
      await MediaLibrary.Asset.create(tempFile.uri);
      return isCurrent() ? 'saved' : 'cancelled';
    } finally {
      try {
        tempFile?.delete();
      } catch {
        // Best-effort cache cleanup must not replace the save result/error.
      }
    }
  };

  return {
    saveImage,
    canOpenSettings: permission?.granted === false && permission?.canAskAgain === false,
  };
}
