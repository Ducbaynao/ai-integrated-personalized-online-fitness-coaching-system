import React from 'react';
import renderer from 'react-test-renderer';
import * as DocumentPicker from 'expo-document-picker';
import { UploadProgressPhotoModal } from '@/features/progress/components/UploadProgressPhotoModal';

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
    expect(tree.root.findByProps({ testID: 'upload-error-text' }).props.children).toBe('Storage unavailable');
    expect(onClose).not.toHaveBeenCalled();
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'btn-submit-upload' }).props.onPress(); });
    expect(onSubmit).toHaveBeenLastCalledWith(expect.objectContaining({ fileUri: asset.uri, mimeType: 'image/png' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
