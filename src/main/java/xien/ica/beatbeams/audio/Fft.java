package xien.ica.beatbeams.audio;

/** Small in-place radix-2 complex FFT. Size must be a power of two. */
final class Fft {
    private final int n;
    private final double[] cos;
    private final double[] sin;
    private final int[] rev;

    Fft(int n) {
        if (Integer.bitCount(n) != 1) throw new IllegalArgumentException("FFT size must be a power of two");
        this.n = n;
        cos = new double[n / 2];
        sin = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            cos[i] = Math.cos(2 * Math.PI * i / n);
            sin[i] = -Math.sin(2 * Math.PI * i / n);
        }
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            rev[i] = Integer.reverse(i) >>> (32 - bits);
        }
    }

    void transform(double[] re, double[] im) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            int halfSize = size >> 1;
            int step = n / size;
            for (int start = 0; start < n; start += size) {
                for (int k = 0, t = 0; k < halfSize; k++, t += step) {
                    int a = start + k;
                    int b = a + halfSize;
                    double tr = re[b] * cos[t] - im[b] * sin[t];
                    double ti = re[b] * sin[t] + im[b] * cos[t];
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }
}
