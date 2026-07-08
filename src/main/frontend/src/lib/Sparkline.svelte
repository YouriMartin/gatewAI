<script lang="ts">
// Tiny dependency-free SVG line chart (on-theme: ships no charting lib).
let { values, color = '#3fb950' }: { values: number[]; color?: string } = $props();

const width = 100;
const height = 28;

let points = $derived(buildPoints(values));

function buildPoints(vals: number[]): string {
  if (vals.length === 0) {
    return '';
  }
  const max = Math.max(...vals, 0);
  const min = Math.min(...vals, 0);
  const range = max - min || 1;
  const step = vals.length > 1 ? width / (vals.length - 1) : 0;
  return vals
    .map((v, i) => {
      const x = i * step;
      const y = height - ((v - min) / range) * height;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(' ');
}
</script>

<svg
  class="sparkline"
  viewBox={`0 0 ${width} ${height}`}
  preserveAspectRatio="none"
  role="img"
>
  <polyline
    {points}
    fill="none"
    stroke={color}
    stroke-width="1.5"
    vector-effect="non-scaling-stroke"
  />
</svg>
