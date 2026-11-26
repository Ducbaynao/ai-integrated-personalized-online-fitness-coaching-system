import React, { useMemo } from 'react';
import { StyleSheet, View } from 'react-native';
import { buildQrMatrix } from '../coachingQrCodec';

interface CoachingQrMatrixViewProps {
  qrUri: string;
  size?: number;
  accessibilityLabel?: string;
  testID?: string;
}

export function CoachingQrMatrixView({
  qrUri,
  size = 220,
  accessibilityLabel = 'Mã QR huấn luyện cá nhân',
  testID = 'coaching-qr-matrix',
}: CoachingQrMatrixViewProps) {
  const matrix = useMemo(() => buildQrMatrix(qrUri), [qrUri]);
  const quietModules = 4;
  const totalModules = matrix.size + quietModules * 2;
  const moduleSize = Math.max(2, Math.floor(size / totalModules));
  const innerSize = matrix.size * moduleSize;
  const framePadding = quietModules * moduleSize;

  const rows = useMemo(() => {
    const result: boolean[][] = [];
    for (let y = 0; y < matrix.size; y++) {
      const row: boolean[] = [];
      for (let x = 0; x < matrix.size; x++) {
        row.push(Boolean(matrix.data[y * matrix.size + x]));
      }
      result.push(row);
    }
    return result;
  }, [matrix]);

  return (
    <View
      testID={testID}
      accessible
      accessibilityRole="image"
      accessibilityLabel={accessibilityLabel}
      style={[
        styles.frame,
        {
          padding: framePadding,
          width: innerSize + framePadding * 2,
          height: innerSize + framePadding * 2,
        },
      ]}
    >
      <View style={{ width: innerSize, height: innerSize }}>
        {rows.map((row, y) => (
          <View key={y} style={styles.row}>
            {row.map((dark, x) => (
              <View
                key={x}
                style={{
                  width: moduleSize,
                  height: moduleSize,
                  backgroundColor: dark ? '#000000' : '#FFFFFF',
                }}
              />
            ))}
          </View>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  frame: {
    backgroundColor: '#FFFFFF',
    alignSelf: 'center',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#E2E8F0',
  },
  row: {
    flexDirection: 'row',
  },
});
