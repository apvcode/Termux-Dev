/**
 * Network Resilience & Error Utilities for devx
 */

export function isNetworkOrRetryableError(err: any): boolean {
  if (!err) return false;
  const msg = (err.message || String(err)).toLowerCase();
  const code = (err.code || err.cause?.code || '').toLowerCase();
  const status = err.status || err.statusCode || 0;

  // HTTP status codes that are retryable (Rate limits, server errors, timeouts)
  if (
    status === 429 ||
    status === 500 ||
    status === 502 ||
    status === 503 ||
    status === 504 ||
    status === 520 ||
    status === 521 ||
    status === 522 ||
    status === 524
  ) {
    return true;
  }

  // Network / Socket error codes
  const networkCodes = [
    'econnreset',
    'etimedout',
    'enotfound',
    'econnrefused',
    'ehostunreach',
    'enetunreach',
    'eai_again',
    'und_err_socket',
    'und_err_connect_timeout',
    'und_err_headers_timeout',
    'und_err_body_timeout',
    'und_err_request_timeout',
    'err_network'
  ];

  if (networkCodes.some(c => code.includes(c))) {
    return true;
  }

  // Common network error messages & fragments
  const networkPatterns = [
    'fetch failed',
    'terminated',
    'socket hang up',
    'network error',
    'network timeout',
    'failed to fetch',
    'premature close',
    'other side closed',
    'connection reset',
    'connection refused',
    'timeout',
    'time out',
    'econnreset',
    'etimedout',
    'enotfound',
    'dns lookup',
    'temporary failure',
    'server error',
    'bad gateway',
    'gateway timeout',
    'overloaded',
    'service unavailable',
    'empty stream',
    'connection closed before receiving response',
    'rate limit'
  ];

  return networkPatterns.some(p => msg.includes(p));
}

export function getHumanReadableNetworkError(err: any): string {
  if (!err) return 'Неизвестная ошибка сети (Network error)';
  const msg = (err.message || String(err)).toLowerCase();
  const code = (err.code || err.cause?.code || '').toLowerCase();
  const status = err.status || err.statusCode || 0;

  if (status === 429 || msg.includes('429') || msg.includes('rate limit')) {
    return 'Превышен лимит запросов API (Rate limit / 429)';
  }
  if (status === 502 || msg.includes('502') || msg.includes('bad gateway')) {
    return 'Шлюз провайдера временно недоступен (Bad Gateway / 502)';
  }
  if (status === 503 || msg.includes('503') || msg.includes('service unavailable')) {
    return 'Сервер провайдера перегружен (Service Unavailable / 503)';
  }
  if (status === 504 || msg.includes('504') || msg.includes('gateway timeout')) {
    return 'Таймаут шлюза провайдера (Gateway Timeout / 504)';
  }
  if (code.includes('enotfound') || msg.includes('enotfound') || code.includes('eai_again')) {
    return 'Нет подключения к сети или DNS (Host not found / Offline)';
  }
  if (
    code.includes('econnreset') ||
    msg.includes('econnreset') ||
    msg.includes('connection reset') ||
    msg.includes('socket hang up') ||
    msg.includes('terminated')
  ) {
    return 'Обрыв соединения с сервером (Connection reset / Socket closed)';
  }
  if (
    code.includes('etimedout') ||
    msg.includes('timeout') ||
    msg.includes('time out') ||
    code.includes('und_err_connect_timeout')
  ) {
    return 'Таймаут сетевого соединения (Network timeout)';
  }
  if (code.includes('econnrefused') || msg.includes('connection refused')) {
    return 'Сервер отклонил подключение (Connection refused)';
  }
  if (msg.includes('fetch failed')) {
    return 'Сбой сетевого соединения (Fetch failed / Connection dropped)';
  }

  return err.message || 'Сбой интернет-соединения';
}

export function waitDelay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal?.aborted) {
      resolve();
      return;
    }
    const timer = setTimeout(() => {
      resolve();
    }, ms);

    if (signal) {
      signal.addEventListener(
        'abort',
        () => {
          clearTimeout(timer);
          resolve();
        },
        { once: true }
      );
    }
  });
}
