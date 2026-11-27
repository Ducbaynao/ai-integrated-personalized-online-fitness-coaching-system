import React from 'react';
import renderer from 'react-test-renderer';
import { Text } from 'react-native';
import * as DocumentPicker from 'expo-document-picker';
import { UploadProgressPhotoModal } from '@/features/progress/components/UploadProgressPhotoModal';
import { ApiError } from '@/types/auth';

jest.mock('expo-document-picker', () => ({ getDocumentAsync: jest.fn() }));

describe('UploadProgressPhotoModal', () => {
  const onSubmit = jest.fn();
  const onClose = jest.fn();
  const asset = { uri: 'file:///cache/a07-test.png', name: 'a07-test.png', mimeType: 'image/png', size: 1024 };

  beforeEach(() => { jest.resetAllMocks(); });

  function renderModal() {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<UploadProgressPhotoModal visible isSubmitting={false} isDark={false} onSubmit={onSubmit} onClose={onClose} />);
    });
    return tree;
  }

  it('requires a real selected image instead of submitting a mock URI', async () => {
    const tree = renderModal();
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-submit-upload' }).props.onPress(); });
    expect(onSubmit).not.toHaveBeenCalled();
    expect(tree.root.findByProps({ testID: 'upload-error-text' }).props.children).toContain('chọn ảnh');
  });

  it('copies the picked PNG into cache and submits its metadata with an ISO instant', async () => {
    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({ canceled: false, assets: [asset] });
    onSubmit.mockResolvedValue(undefined);
    const tree = renderModal();
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-select-photo' }).props.onPress(); });
    expect(DocumentPicker.getDocumentAsync).toHaveBeenCalledWith({ type: ['image/jpeg', 'image/png'], copyToCacheDirectory: true, multiple: false });
    renderer.act(() => { tree.root.findByProps({ testID: 'input-photo-weight' }).props.onChangeText('70'); });
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-submit-upload' }).props.onPress(); });
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ fileUri: asset.uri, fileName: asset.name, mimeType: 'image/png', weightAtTime: 70, weightUnitCode: 'KG', takenAt: expect.stringMatching(/^\d{4}-\d{2}-\d{2}T.+Z$/) }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('does not upload when the picker is cancelled', async () => {
    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({ canceled: true, assets: null });
    const tree = renderModal();
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-select-photo' }).props.onPress(); });
    expect(onSubmit).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('rejects oversized images before upload', async () => {
    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({ canceled: false, assets: [{ ...asset, size: 11 * 1024 * 1024 }] });
    const tree = renderModal();
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-select-photo' }).props.onPress(); });
    expect(tree.root.findByProps({ testID: 'upload-error-text' }).props.children).toContain('10 MB');
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('retains the image and form values when upload fails so the user can retry', async () => {
    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({ canceled: false, assets: [asset] });
    onSubmit.mockRejectedValueOnce(new Error('Storage unavailable')).mockResolvedValueOnce(undefined);
    const tree = renderModal();
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-select-photo' }).props.onPress(); });
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-submit-upload' }).props.onPress(); });
    expect(tree.root.findByProps({ testID: 'upload-error-text' }).props.children).toBe('Không thể tải ảnh lên. Vui lòng thử lại.');
    expect(onClose).not.toHaveBeenCalled();
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-submit-upload' }).props.onPress(); });
    expect(onSubmit).toHaveBeenLastCalledWith(expect.objectContaining({ fileUri: asset.uri, mimeType: 'image/png' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it.each(['SCANNER_UNAVAILABLE', 'STORAGE_UNAVAILABLE'])('shows photo copy for %s and retains the draft for one explicit retry', async errorCode => {
    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({ canceled: false, assets: [asset] });
    onSubmit.mockRejectedValueOnce(new ApiError(503, 'sensitive server detail', { errorCode } as any)).mockResolvedValueOnce(undefined);
    const tree = renderModal();
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-select-photo' }).props.onPress(); });
    renderer.act(() => {
      tree.root.findByProps({ testID: 'view-type-BACK' }).props.onPress();
      tree.root.findByProps({ testID: 'input-photo-weight' }).props.onChangeText('70');
      tree.root.findByProps({ testID: 'input-photo-notes' }).props.onChangeText('fixture note');
    });
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-submit-upload' }).props.onPress(); });
    const message = tree.root.findByProps({ testID: 'upload-error-text' }).props.children;
    expect(message).toContain('ảnh');
    expect(message).not.toMatch(/hồ sơ|sensitive/);
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
    expect(tree.root.findByProps({ testID: 'btn-submit-upload' }).props.disabled).toBe(false);
    expect(tree.root.findAllByType(Text).some(node => node.props.children === 'Thử tải lại')).toBe(true);
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-submit-upload' }).props.onPress(); });
    expect(onSubmit).toHaveBeenCalledTimes(2);
    expect(onSubmit).toHaveBeenLastCalledWith(expect.objectContaining({ fileUri: asset.uri, viewType: 'BACK', weightAtTime: 70, notes: 'fixture note' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('ignores an upload completion after the form is unmounted', async () => {
    let finish!: () => void;
    onSubmit.mockReturnValue(new Promise<void>(resolve => { finish = resolve; }));
    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({ canceled: false, assets: [asset] });
    const tree = renderModal();
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-select-photo' }).props.onPress(); });
    let pending!: Promise<void>;
    renderer.act(() => { pending = tree.root.findByProps({ testID: 'btn-submit-upload' }).props.onPress(); });
    renderer.act(() => tree.unmount());
    await renderer.act(async () => { finish(); await pending; });
    expect(onClose).not.toHaveBeenCalled();
  });

  it('blocks a second press while the first upload is pending', async () => {
    let finish!: () => void;
    onSubmit.mockReturnValue(new Promise<void>(resolve => { finish = resolve; }));
    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({ canceled: false, assets: [asset] });
    const tree = renderModal();
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-select-photo' }).props.onPress(); });
    let pending!: Promise<void>;
    renderer.act(() => {
      const press = tree.root.findByProps({ testID: 'btn-submit-upload' }).props.onPress;
      pending = press();
      void press();
    });
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(tree.root.findByProps({ testID: 'btn-submit-upload' }).props.disabled).toBe(true);
    await renderer.act(async () => { finish(); await pending; });
  });
});
