-- 명령 타임아웃을 '생성 시각'이 아니라 '마지막 상태 변경 시각' 기준으로 계산하기 위한 컬럼
alter table command_logs add column updated_at timestamp with time zone;
update command_logs set updated_at = created_at;
