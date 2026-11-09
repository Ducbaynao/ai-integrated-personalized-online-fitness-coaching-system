import React from 'react';
import renderer from 'react-test-renderer';
import { MeasurementsScreen } from '@/features/measurements/MeasurementsScreen';
import { measurementApi } from '@/services/measurementApi';
import { Measurement, MetricDefinition } from '@/types/measurement';

jest.mock('@/services/measurementApi', () => ({
  measurementApi: {
    getMetricDefinitions: jest.fn(),
    getMeasurements: jest.fn(),
    recordMeasurement: jest.fn(),
    correctMeasurement: jest.fn(),
  },
}));

describe('MeasurementsScreen', () => {
  const sampleMetrics: MetricDefinition[] = [
    {
      id: 1,
      code: 'WEIGHT',
      displayName: 'Cân nặng',
      valueType: 'NUMERIC',
      defaultUnitCode: 'KG',
      defaultUnitSymbol: 'kg',
      validMin: 30,
      validMax: 300,
      isActive: true,
    },
    {
      id: 2,
      code: 'WAIST',
      displayName: 'Vòng eo',
      valueType: 'NUMERIC',
      defaultUnitCode: 'CM',
      defaultUnitSymbol: 'cm',
      validMin: 40,
      validMax: 200,
      isActive: true,
    },
  ];

  const sampleMeasurements: Measurement[] = [
    {
      id: 'm-1',
      studentId: 'student-1',
      metricDefinitionId: 1,
      metricCode: 'WEIGHT',
      metricDisplayName: 'Cân nặng',
      numericValue: 72.5,
      unitCode: 'KG',
      unitSymbol: 'kg',
      measuredAt: '2026-11-08T07:00:00Z',
      enteredBy: 'student-1',
      validationStatus: 'VALID',
      createdAt: '2026-11-08T07:00:00Z',
    },
    {
      id: 'm-2',
      studentId: 'student-1',
      metricDefinitionId: 1,
      metricCode: 'WEIGHT',
      metricDisplayName: 'Cân nặng',
      numericValue: 95.0,
      unitCode: 'KG',
      unitSymbol: 'kg',
      measuredAt: '2026-11-07T07:00:00Z',
      enteredBy: 'student-1',
      validationStatus: 'SUSPECT',
      notes: 'Giá trị tăng bất thường',
      createdAt: '2026-11-07T07:00:00Z',
    },
  ];

  beforeEach(() => {
    jest.clearAllMocks();
  });

  const settle = async () => {
    await renderer.act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30));
    });
  };

  it('renders loading state initially and then displays measurement list', async () => {
    (measurementApi.getMetricDefinitions as jest.Mock).mockResolvedValueOnce(sampleMetrics);
    (measurementApi.getMeasurements as jest.Mock).mockResolvedValueOnce({
      content: sampleMeasurements,
      totalElements: 2,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<MeasurementsScreen studentId="student-1" />);
    });

    // Loading should be visible before promises settle
    expect(tree.root.findByProps({ testID: 'measurements-loading' })).toBeDefined();

    await settle();

    // After settle, items should be rendered
    const item1 = tree.root.findByProps({ testID: 'measurement-item-m-1' });
    const item2 = tree.root.findByProps({ testID: 'measurement-item-m-2' });
    expect(item1).toBeDefined();
    expect(item2).toBeDefined();
  });

  it('renders empty state when measurement content is empty', async () => {
    (measurementApi.getMetricDefinitions as jest.Mock).mockResolvedValueOnce(sampleMetrics);
    (measurementApi.getMeasurements as jest.Mock).mockResolvedValueOnce({
      content: [],
      totalElements: 0,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<MeasurementsScreen studentId="student-1" />);
    });

    await settle();

    const emptyBox = tree.root.findByProps({ testID: 'measurements-empty' });
    expect(emptyBox).toBeDefined();
  });

  it('renders error state and handles retry', async () => {
    (measurementApi.getMetricDefinitions as jest.Mock).mockRejectedValueOnce(new Error('Network connection failed'));
    (measurementApi.getMeasurements as jest.Mock).mockResolvedValueOnce({ content: [], totalElements: 0 });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<MeasurementsScreen studentId="student-1" />);
    });

    await settle();

    const errorView = tree.root.findByProps({ testID: 'measurements-error' });
    expect(errorView).toBeDefined();

    // Now mock success for retry
    (measurementApi.getMetricDefinitions as jest.Mock).mockResolvedValueOnce(sampleMetrics);
    (measurementApi.getMeasurements as jest.Mock).mockResolvedValueOnce({
      content: sampleMeasurements,
      totalElements: 2,
    });

    const retryBtn = tree.root.findByProps({ testID: 'measurements-retry-btn' });
    await renderer.act(async () => {
      retryBtn.props.onPress();
    });

    await settle();

    expect(tree.root.findByProps({ testID: 'measurement-item-m-1' })).toBeDefined();
  });

  it('filters measurements when selecting a metric chip', async () => {
    (measurementApi.getMetricDefinitions as jest.Mock).mockResolvedValue(sampleMetrics);
    (measurementApi.getMeasurements as jest.Mock).mockResolvedValue({
      content: sampleMeasurements,
      totalElements: 2,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<MeasurementsScreen studentId="student-1" />);
    });

    await settle();

    const waistFilterChip = tree.root.findByProps({ testID: 'filter-WAIST' });
    await renderer.act(async () => {
      waistFilterChip.props.onPress();
    });

    await settle();

    expect(measurementApi.getMeasurements).toHaveBeenCalledWith(
      expect.objectContaining({
        studentId: 'student-1',
        metricCode: 'WAIST',
      })
    );
  });

  it('hides add and correct buttons when in readOnly mode', async () => {
    (measurementApi.getMetricDefinitions as jest.Mock).mockResolvedValueOnce(sampleMetrics);
    (measurementApi.getMeasurements as jest.Mock).mockResolvedValueOnce({
      content: sampleMeasurements,
      totalElements: 2,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<MeasurementsScreen studentId="student-1" readOnly={true} />);
    });

    await settle();

    // Add button should not exist
    const addBtns = tree.root.findAllByProps({ testID: 'add-measurement-btn' });
    expect(addBtns).toHaveLength(0);

    // Correct buttons should not exist
    const correctBtns = tree.root.findAllByProps({ testID: 'btn-correct-m-1' });
    expect(correctBtns).toHaveLength(0);
  });
});
