import 'dart:async';
import 'dart:convert';
import 'dart:developer' as developer;

import 'package:flutter/foundation.dart';

enum McpNavigationResult { handled, accepted, rejected }

/// Call once in the main isolate, after the app's URI handler is ready.
/// Reuse the same handler that receives links from Android or iOS.
void registerMcpNavigation(
  FutureOr<McpNavigationResult> Function(Uri uri) navigate,
) {
  if (!kDebugMode) return;

  developer.registerExtension('ext.flutter_debug_mcp.navigate', (
    method,
    parameters,
  ) async {
    final raw = parameters['uri'];
    final uri = raw == null ? null : Uri.tryParse(raw);
    if (uri == null || !uri.hasScheme) {
      return _response(McpNavigationResult.rejected);
    }
    return _response(await navigate(uri));
  });
}

developer.ServiceExtensionResponse _response(McpNavigationResult result) {
  return developer.ServiceExtensionResponse.result(
    jsonEncode({'status': result.name}),
  );
}
