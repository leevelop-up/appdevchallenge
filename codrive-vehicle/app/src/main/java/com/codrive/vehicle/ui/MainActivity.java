package com.codrive.vehicle.ui;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.codrive.vehicle.BuildConfig;
import com.codrive.vehicle.CoDriveController;
import com.codrive.vehicle.CoDriveController.UiState;
import com.codrive.vehicle.R;
import com.codrive.vehicle.data.Settings;
import com.codrive.vehicle.model.Models;
import com.codrive.vehicle.model.ScreenResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements ParticipantAdapter.Actions {
    private static final String[] ROLES = {"MAP", "HVAC", "LIGHT", "MUSIC"};

    private MainViewModel vm;
    private CoDriveController ctl;
    private ParticipantAdapter adapter;
    private UiState last;
    private String renderedQrUrl;
    private Bitmap qrBitmap;
    private AlertDialog qrDialog;
    private final Set<String> promptedRole = new HashSet<>();
    private final Handler ticker = new Handler(Looper.getMainLooper());

    private View screenConnecting, screenError, screenQr, screenUsers, cardApproval, cardProposal;
    private TextView tvConn, tvConnecting, tvError, tvCode, tvExpiry, tvCount, tvApproval, tvBanner, tvStatus, tvProposal;
    private ImageView ivQr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        vm = new ViewModelProvider(this).get(MainViewModel.class);
        ctl = vm.controller();

        screenConnecting = findViewById(R.id.screenConnecting);
        screenError = findViewById(R.id.screenError);
        screenQr = findViewById(R.id.screenQr);
        screenUsers = findViewById(R.id.screenUsers);
        cardApproval = findViewById(R.id.cardApproval);
        cardProposal = findViewById(R.id.cardProposal);
        tvProposal = findViewById(R.id.tvProposal);
        tvConn = findViewById(R.id.tvConn);
        tvConnecting = findViewById(R.id.tvConnecting);
        tvError = findViewById(R.id.tvError);
        tvCode = findViewById(R.id.tvCode);
        tvExpiry = findViewById(R.id.tvExpiry);
        tvCount = findViewById(R.id.tvCount);
        tvApproval = findViewById(R.id.tvApproval);
        tvBanner = findViewById(R.id.tvBanner);
        tvStatus = findViewById(R.id.tvStatus);
        ivQr = findViewById(R.id.ivQr);

        adapter = new ParticipantAdapter(this);
        RecyclerView rv = findViewById(R.id.rvUsers);

        int span = Math.max(1, Math.min(3, getResources().getConfiguration().screenWidthDp / 360));
        rv.setLayoutManager(new GridLayoutManager(this, span));
        applyOrientationLayout();
        rv.setAdapter(adapter);

        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());
        findViewById(R.id.btnConfig).setOnClickListener(v -> showSettings());
        findViewById(R.id.btnRetry).setOnClickListener(v -> ctl.retry());
        findViewById(R.id.btnRefreshQr).setOnClickListener(v -> ctl.refreshQr());
        findViewById(R.id.btnAdd).setOnClickListener(v -> ctl.addPassenger());
        findViewById(R.id.btnManage).setOnClickListener(v -> showManage());
        findViewById(R.id.btnEndAll).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("모든 연결을 종료할까요?")
                .setMessage("연결된 동승자의 권한이 모두 해제되고 새 QR코드가 표시됩니다.")
                .setPositiveButton("모두 종료", (d, w) -> ctl.endAll()).setNegativeButton("취소", null).show());
        findViewById(R.id.btnApprove).setOnClickListener(v -> {
            if (last != null && last.snapshot != null && !last.snapshot.pending.isEmpty()) {
                ctl.approve(last.snapshot.pending.get(0).requestId);
            }
        });
        findViewById(R.id.btnReject).setOnClickListener(v -> {
            if (last != null && last.snapshot != null && !last.snapshot.pending.isEmpty()) {
                ctl.reject(last.snapshot.pending.get(0).requestId);
            }
        });

        findViewById(R.id.btnAccept).setOnClickListener(v -> {
            if (last != null && !last.proposals.isEmpty()) {
                ctl.acceptProposal(last.proposals.get(0).requestId);
            }
        });
        findViewById(R.id.btnDecline).setOnClickListener(v -> {
            if (last != null && !last.proposals.isEmpty()) {
                ctl.declineProposal(last.proposals.get(0).requestId);
            }
        });

        if (!BuildConfig.DEBUG) {
            findViewById(R.id.btnSettings).setVisibility(View.GONE);
            findViewById(R.id.btnConfig).setVisibility(View.GONE);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        });

        vm.state().observe(this, this::render);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ticker.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ticker.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateExpiry();
            ticker.postDelayed(this, 1000);
        }
    };

    private void render(UiState st) {
        last = st;
        ScreenResolver.Screen sc = st.screen;
        show(screenConnecting, sc == ScreenResolver.Screen.CONNECTING);
        show(screenError, sc == ScreenResolver.Screen.ERROR);
        show(screenQr, sc == ScreenResolver.Screen.QR);
        show(screenUsers, sc == ScreenResolver.Screen.USERS);

        int count = st.snapshot == null ? 0 : st.snapshot.participants.size();
        int max = st.snapshot == null ? 3 : st.snapshot.maxParticipants;
        tvConn.setText(switch (st.connection) {
            case CONNECTED -> count == 0 ? "연결 대기 · 0/" + max + "명" : "현재 연결 중 · " + count + "/" + max + "명";
            case RECONNECTING -> "재연결 중...";
            case ERROR -> "연결 오류";
            default -> "서버 연결 중...";
        });
        tvConn.setTextColor(getColor(st.connection == ScreenResolver.Connection.CONNECTED ? R.color.ok
                : st.connection == ScreenResolver.Connection.ERROR ? R.color.bad : R.color.warn));

        if (sc == ScreenResolver.Screen.ERROR) {
            tvError.setText(st.message == null ? "서버에 연결할 수 없습니다." : st.message);
        }
        if (sc == ScreenResolver.Screen.CONNECTING) {
            tvConnecting.setText(st.message == null ? "서버에 연결하는 중..." : st.message);
        }
        if (sc == ScreenResolver.Screen.QR) {
            renderQr(st);
            tvCount.setText("연결 인원 " + count + "/" + max + "명");
        }
        if (sc == ScreenResolver.Screen.USERS) {
            adapter.submit(st.snapshot.participants, st.driving);
            findViewById(R.id.btnAdd).setEnabled(count < max);
            findViewById(R.id.btnManage).setEnabled(!st.driving);
            findViewById(R.id.btnEndAll).setEnabled(!st.driving);
            promptRoleIfNeeded(st);
        }
        renderApproval(st);
        renderProposals(st);
        renderQrOverlay(st);

        List<String> notes = new ArrayList<>();
        if (st.driving) {
            notes.add("주행 중에는 승인·권한 변경을 할 수 없습니다. 정차 후 이용해주세요.");
        }
        if ("MOCK".equals(st.mode)) {
            notes.add("MOCK 모드: 시뮬레이션이며 실제 차량은 제어되지 않습니다.");
        }
        if (st.message != null && sc != ScreenResolver.Screen.ERROR && sc != ScreenResolver.Screen.CONNECTING) {
            notes.add(st.message);
        }
        tvBanner.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
        tvBanner.setText(String.join("\n", notes));
        String net = st.connection == ScreenResolver.Connection.CONNECTED ? "정상" : "확인 필요";
        tvStatus.setText(BuildConfig.DEBUG
                ? "서버 " + ctl.settings().serverUrl() + " · 네트워크 " + net
                        + " · SDK 모드 " + ("PLEOS".equals(st.mode) ? "Pleos(실차)" : "Mock(시뮬레이션)")
                : "네트워크 " + net);
    }

    private void renderQr(UiState st) {
        if (st.qr == null) {
            ivQr.setImageDrawable(null);
            tvCode.setText("QR 발급 중...");
            renderedQrUrl = null;
            return;
        }
        if (!st.qr.joinUrl.equals(renderedQrUrl)) {
            try {
                qrBitmap = QrRenderer.render(st.qr.joinUrl, 640);
                ivQr.setImageBitmap(qrBitmap);
                renderedQrUrl = st.qr.joinUrl;
            } catch (Exception e) {
                ivQr.setImageDrawable(null);
            }
        }
        tvCode.setText("참여 코드  " + st.qr.code);
        updateExpiry();
    }

    private void updateExpiry() {
        if (last == null || last.qr == null || tvExpiry == null) {
            return;
        }
        long sec = last.qr.remainingMillis() / 1000;
        tvExpiry.setText(String.format("QR 만료까지 %d:%02d", sec / 60, sec % 60));
    }

    private void renderProposals(UiState st) {
        boolean has = !st.proposals.isEmpty() && st.screen != ScreenResolver.Screen.ERROR;
        show(cardProposal, has);
        if (has) {
            com.codrive.vehicle.model.ProposalTracker.Proposal p = st.proposals.get(0);
            String kind = "NAV_WAYPOINT".equals(p.action) ? "경유지" : "목적지";
            tvProposal.setText(p.from + "님의 " + kind + " 제안: " + p.placeName
                    + (st.proposals.size() > 1 ? "  (외 " + (st.proposals.size() - 1) + "건)" : ""));
        }
    }

    private void applyOrientationLayout() {
        android.content.res.Configuration c = getResources().getConfiguration();
        boolean portrait = c.screenHeightDp > c.screenWidthDp;
        LinearLayout row = findViewById(R.id.qrRow);
        View info = findViewById(R.id.qrInfo);
        row.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        LinearLayout header = findViewById(R.id.header);
        View title = findViewById(R.id.tvTitle);
        View actions = findViewById(R.id.headerActions);
        header.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tp = (LinearLayout.LayoutParams) title.getLayoutParams();
        tp.width = portrait ? LinearLayout.LayoutParams.MATCH_PARENT : 0;
        tp.weight = portrait ? 0 : 1;
        title.setLayoutParams(tp);
        LinearLayout.LayoutParams ap = (LinearLayout.LayoutParams) actions.getLayoutParams();
        ap.topMargin = portrait ? (int) (8 * getResources().getDisplayMetrics().density) : 0;
        actions.setLayoutParams(ap);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) info.getLayoutParams();
        float dp = getResources().getDisplayMetrics().density;
        lp.setMarginStart(portrait ? 0 : (int) (32 * dp));
        lp.topMargin = portrait ? (int) (24 * dp) : 0;
        info.setLayoutParams(lp);
    }

    private void renderApproval(UiState st) {
        Models.Snapshot s = st.snapshot;
        boolean has = s != null && !s.pending.isEmpty() && st.screen != ScreenResolver.Screen.ERROR;
        show(cardApproval, has);
        if (has) {
            Models.PendingInfo p = s.pending.get(0);
            tvApproval.setText("새로운 참여 요청: " + p.nickname + (s.pending.size() > 1 ? "  (외 " + (s.pending.size() - 1) + "건)" : ""));
            findViewById(R.id.btnApprove).setEnabled(!st.driving);
            findViewById(R.id.btnReject).setEnabled(!st.driving);
        }
    }

    private void promptRoleIfNeeded(UiState st) {
        if (st.driving || st.snapshot == null) {
            return;
        }
        for (Models.ParticipantInfo p : st.snapshot.participants) {
            if ("NONE".equals(p.role) && promptedRole.add(p.id)) {
                showRoleDialog(p);
                return;
            }
        }
    }

    private void renderQrOverlay(UiState st) {
        if (st.qrOverlay && st.qr != null) {
            if (qrDialog == null) {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(32, 32, 32, 16);
                ImageView iv = new ImageView(this);
                iv.setId(View.generateViewId());
                iv.setBackgroundColor(0xFFFFFFFF);
                box.addView(iv, new LinearLayout.LayoutParams(520, 520));
                TextView code = new TextView(this);
                code.setTextSize(28);
                code.setPadding(0, 16, 0, 0);
                box.addView(code);
                qrDialog = new AlertDialog.Builder(this).setTitle("동승자 추가 - QR코드를 스캔하세요").setView(box)
                        .setNegativeButton("닫기", null)
                        .setOnDismissListener(d -> {
                            qrDialog = null;
                            ctl.closeQrOverlay();
                        }).create();
                qrDialog.show();
                qrDialog.getWindow().getDecorView().setTag(new View[]{iv, code});
            }
            View[] v = (View[]) qrDialog.getWindow().getDecorView().getTag();
            try {
                ((ImageView) v[0]).setImageBitmap(QrRenderer.render(st.qr.joinUrl, 520));
            } catch (Exception ignored) {
            }
            ((TextView) v[1]).setText("참여 코드  " + st.qr.code);
        } else if (qrDialog != null) {
            AlertDialog d = qrDialog;
            qrDialog = null;
            d.setOnDismissListener(null);
            d.dismiss();
        }
    }

    private static void show(View v, boolean on) {
        v.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onPermissions(Models.ParticipantInfo p) {
        new AlertDialog.Builder(this).setTitle(p.nickname + " · " + Labels.role(p.role))
                .setItems(new String[]{"역할 변경", "기능 권한 설정"}, (d, which) -> {
                    if (which == 0) {
                        showRoleDialog(p);
                    } else {
                        showFeatureDialog(p);
                    }
                }).setNegativeButton("닫기", null).show();
    }

    @Override
    public void onRemove(Models.ParticipantInfo p) {
        new AlertDialog.Builder(this).setTitle(p.nickname + "님 연결을 해제할까요?")
                .setMessage("해제하면 이 동승자의 모든 권한이 사라지고 다시 QR코드로 참여해야 합니다.")
                .setPositiveButton("연결 해제", (d, w) -> ctl.removeParticipant(p.id))
                .setNegativeButton("취소", null).show();
    }

    private void showManage() {
        if (last == null || last.snapshot == null || last.snapshot.participants.isEmpty()) {
            return;
        }
        List<Models.ParticipantInfo> ps = last.snapshot.participants;
        String[] names = new String[ps.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = ps.get(i).nickname + " · " + Labels.role(ps.get(i).role);
        }
        new AlertDialog.Builder(this).setTitle("공유 기능 관리 - 사용자를 선택하세요")
                .setItems(names, (d, i) -> showFeatureDialog(ps.get(i))).setNegativeButton("닫기", null).show();
    }

    private void showRoleDialog(Models.ParticipantInfo p) {
        String[] names = {Labels.role("MAP"), Labels.role("HVAC"), Labels.role("LIGHT"), Labels.role("MUSIC")};
        int[] sel = {indexOf(p.role)};
        new AlertDialog.Builder(this).setTitle(p.nickname + "님의 역할")
                .setSingleChoiceItems(names, sel[0], (d, which) -> sel[0] = which)
                .setPositiveButton("배정", (d, w) -> {
                    if (sel[0] >= 0) {
                        ctl.assignRole(p.id, ROLES[sel[0]]);
                    }
                }).setNegativeButton("나중에", null).show();
    }

    private void showFeatureDialog(Models.ParticipantInfo p) {
        List<Models.FeatureInfo> controls = new ArrayList<>();
        StringBuilder info = new StringBuilder();
        for (Models.FeatureInfo f : p.features) {
            if ("CONTROL".equals(f.kind)) {
                controls.add(f);
            } else {
                info.append("• ").append(Labels.feature(f.code)).append(" - ").append(Labels.status(f.status)).append('\n');
            }
        }
        if (p.features.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(p.nickname).setMessage("역할을 먼저 배정해주세요.")
                    .setPositiveButton("확인", null).show();
            return;
        }

        boolean map = "MAP".equals(p.role);
        int extra = map ? 1 : 0;
        String[] items = new String[controls.size() + extra];
        boolean[] checked = new boolean[controls.size() + extra];
        for (int i = 0; i < controls.size(); i++) {
            Models.FeatureInfo f = controls.get(i);
            items[i] = Labels.feature(f.code) + "  [" + Labels.status(f.status) + "]";
            checked[i] = "CONTROL".equals(f.access);
        }
        if (map) {
            items[controls.size()] = "요청마다 내가 확인한 뒤 적용 (끄면 바로 적용)";
            checked[controls.size()] = ctl.settings().confirmRouteChanges();
        }
        boolean[] original = checked.clone();

        TextView title = new TextView(this);
        title.setPadding(48, 32, 48, 8);
        title.setTextSize(18);
        title.setTextColor(getColor(R.color.text));
        String hint = controls.isEmpty() ? "" : "체크하면 제어 권한을 부여합니다. 차량이 지원하지 않는 기능은 체크해도 사용할 수 없습니다.";
        title.setText(p.nickname + " · 제어 권한\n" + info.toString().trim() + (hint.isEmpty() ? "" : "\n" + hint));
        AlertDialog.Builder b = new AlertDialog.Builder(this).setCustomTitle(title);
        if (controls.isEmpty() && !map) {
            b.setPositiveButton("확인", null);
        } else {
            b.setMultiChoiceItems(items, checked, (d, i, on) -> checked[i] = on)
                    .setPositiveButton("저장", (d, w) -> {
                        for (int i = 0; i < controls.size(); i++) {
                            if (checked[i] != original[i]) {
                                ctl.setAccess(p.id, controls.get(i).code, checked[i] ? "CONTROL" : "NONE");
                            }
                        }
                        if (map) {
                            ctl.settings().setConfirmRouteChanges(checked[controls.size()]);
                        }
                    }).setNegativeButton("취소", null);
        }
        b.show();
    }

    private void showSettings() {
        View v = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        EditText et = v.findViewById(R.id.etServer);
        Settings s = ctl.settings();
        et.setText(s.serverUrl());
        RadioButton mock = v.findViewById(R.id.rbMock), pleos = v.findViewById(R.id.rbPleos);
        (Settings.MODE_PLEOS.equals(s.mode()) ? pleos : mock).setChecked(true);
        Switch sw = v.findViewById(R.id.swDriving);
        sw.setChecked(s.mockDriving());
        new AlertDialog.Builder(this).setTitle("연결 설정").setView(v)
                .setPositiveButton("저장 후 다시 연결", (d, w) -> {
                    String url = et.getText().toString().trim();
                    if (url.isEmpty()) {
                        return;
                    }
                    s.save(url, pleos.isChecked() ? Settings.MODE_PLEOS : Settings.MODE_MOCK, sw.isChecked());
                    ctl.start();
                }).setNegativeButton("취소", null).show();
    }

    private static int indexOf(String role) {
        for (int i = 0; i < ROLES.length; i++) {
            if (ROLES[i].equals(role)) {
                return i;
            }
        }
        return -1;
    }
}
