import { StyleSheet } from 'react-native';
import type { ReactTestRenderer } from 'react-test-renderer';
import { fontFamily } from '@/design-system/tokens/typography';

const APP_FAMILIES = new Set<string>(Object.values(fontFamily));

/**
 * Flattens the style of every rendered Text/TextInput (so styles composed across arrays and nested Text
 * are resolved the way React Native resolves them) and returns the ones that ask for a weight or italic on
 * top of an app font family. Such a combination makes the platform synthesise bold/oblique from the
 * registered file instead of using the real Be Vietnam Pro face.
 */
export function syntheticFaceOffenders(tree: ReactTestRenderer): string[] {
  const offenders: string[] = [];
  for (const node of tree.root.findAll((n) => (n.type as unknown) === 'Text' || (n.type as unknown) === 'TextInput')) {
    const flat = StyleSheet.flatten(node.props.style) as { fontFamily?: string; fontWeight?: unknown; fontStyle?: unknown } | undefined;
    if (flat?.fontFamily && APP_FAMILIES.has(flat.fontFamily) && (flat.fontWeight !== undefined || flat.fontStyle !== undefined)) {
      const text = node.children.map((child) => (typeof child === 'string' ? child : '')).join('').slice(0, 40);
      offenders.push(`${flat.fontFamily} + fontWeight=${String(flat.fontWeight)} fontStyle=${String(flat.fontStyle)} "${text}"`);
    }
  }
  return offenders;
}

/** Families requested by the rendered Text nodes, after flattening. */
export function renderedFamilies(tree: ReactTestRenderer): Set<string> {
  const families = new Set<string>();
  for (const node of tree.root.findAll((n) => (n.type as unknown) === 'Text' || (n.type as unknown) === 'TextInput')) {
    const flat = StyleSheet.flatten(node.props.style) as { fontFamily?: string } | undefined;
    if (flat?.fontFamily) families.add(flat.fontFamily);
  }
  return families;
}
