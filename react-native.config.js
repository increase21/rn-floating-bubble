/**
 * react-native.config.js
 *
 * Tells the React Native CLI auto-linker exactly where the Android source lives
 * and how to register the native package.  This file is read by
 * `react-native config` (RN ≥ 0.60) and is not bundled into the JS output.
 */
module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        packageImportPath: 'import com.rnfloatingbubble.FloatingBubblePackage;',
        packageInstance: 'new FloatingBubblePackage()',
      },
      // iOS has no native implementation; the podspec compiles a stub so that
      // `pod install` succeeds without errors.  All API calls no-op on iOS.
      ios: {
        podspecPath: './rn-floating-bubble.podspec',
      },
    },
  },
};
