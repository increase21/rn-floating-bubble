import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Linking,
  Platform,
  Pressable,
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  View,
} from 'react-native';
import FloatingBubble from '@increase21/rn-floating-bubble';

export default function App() {
  const [hasPermission, setHasPermission] = useState(false);
  const [isShowing, setIsShowing]         = useState(false);

  // Check permission & bubble state on mount and on focus
  const refreshState = useCallback(async () => {
    if (Platform.OS !== 'android') return;
    const [perm, showing] = await Promise.all([
      FloatingBubble.hasPermission(),
      FloatingBubble.isShowing(),
    ]);
    setHasPermission(perm);
    setIsShowing(showing);
  }, []);

  useEffect(() => {
    refreshState();
    const sub = Linking.addEventListener('url', refreshState);
    return () => sub.remove();
  }, [refreshState]);

  // -------------------------------------------------------------------------

  const handlePermissionRequest = useCallback(async () => {
    const granted = await FloatingBubble.requestPermission();
    setHasPermission(granted);
    if (!granted) {
      Alert.alert(
        'Permission required',
        'Please enable "Display over other apps" in Settings and come back.',
        [{ text: 'OK' }]
      );
    }
  }, []);

  const handleToggleBubble = useCallback(async (value: boolean) => {
    if (!hasPermission) {
      await handlePermissionRequest();
      return;
    }
    if (value) {
      await FloatingBubble.show({
        size: 60,
        color: 0xff4285f4, // Google Blue
        initialX: 0,
        initialY: 200,
      });
    } else {
      await FloatingBubble.hide();
    }
    setIsShowing(value);
  }, [hasPermission, handlePermissionRequest]);

  // -------------------------------------------------------------------------

  if (Platform.OS !== 'android') {
    return (
      <SafeAreaView style={styles.container}>
        <Text style={styles.unsupported}>
          FloatingBubble is Android-only.
        </Text>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="dark-content" />

      <Text style={styles.title}>Floating Bubble Demo</Text>
      <Text style={styles.subtitle}>
        The bubble floats over all other apps.{'\n'}
        Tap it to return here.
      </Text>

      {/* Permission card */}
      <View style={styles.card}>
        <View style={styles.row}>
          <Text style={styles.label}>Overlay permission</Text>
          <View style={[styles.badge, hasPermission ? styles.badgeOk : styles.badgeDenied]}>
            <Text style={styles.badgeText}>
              {hasPermission ? 'Granted' : 'Denied'}
            </Text>
          </View>
        </View>
        {!hasPermission && (
          <Pressable style={styles.button} onPress={handlePermissionRequest}>
            <Text style={styles.buttonText}>Grant permission</Text>
          </Pressable>
        )}
      </View>

      {/* Bubble toggle card */}
      <View style={styles.card}>
        <View style={styles.row}>
          <Text style={styles.label}>Show bubble</Text>
          <Switch
            value={isShowing}
            onValueChange={handleToggleBubble}
            disabled={!hasPermission}
            trackColor={{ true: '#4285F4' }}
            thumbColor={isShowing ? '#fff' : '#f4f3f4'}
          />
        </View>
        {isShowing && (
          <Text style={styles.hint}>
            Press the Home button — the bubble will float on your home screen.
            Tap the bubble to come back.
          </Text>
        )}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F7FA',
    padding: 24,
  },
  title: {
    fontSize: 26,
    fontWeight: '700',
    color: '#1A1A2E',
    marginBottom: 8,
  },
  subtitle: {
    fontSize: 14,
    color: '#666',
    lineHeight: 20,
    marginBottom: 32,
  },
  card: {
    backgroundColor: '#fff',
    borderRadius: 16,
    padding: 20,
    marginBottom: 16,
    shadowColor: '#000',
    shadowOpacity: 0.06,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 2 },
    elevation: 3,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  label: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1A1A2E',
  },
  badge: {
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 4,
  },
  badgeOk: {
    backgroundColor: '#E6F4EA',
  },
  badgeDenied: {
    backgroundColor: '#FDE7E9',
  },
  badgeText: {
    fontSize: 12,
    fontWeight: '600',
    color: '#1A1A2E',
  },
  button: {
    marginTop: 16,
    backgroundColor: '#4285F4',
    borderRadius: 12,
    paddingVertical: 12,
    alignItems: 'center',
  },
  buttonText: {
    color: '#fff',
    fontWeight: '600',
    fontSize: 15,
  },
  hint: {
    marginTop: 12,
    fontSize: 13,
    color: '#888',
    lineHeight: 18,
  },
  unsupported: {
    fontSize: 16,
    color: '#666',
    textAlign: 'center',
    marginTop: 100,
  },
});
