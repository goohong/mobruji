import os
import sys
import discord
import asyncio
from pathlib import Path
from dotenv import load_dotenv

# bot.py 경로 추가
sys.path.insert(0, str(Path(__file__).resolve().parent))

async def setup_server():
    load_dotenv()
    token = os.environ.get("DISCORD_BOT_TOKEN")
    if not token:
        print("Error: DISCORD_BOT_TOKEN not found in .env")
        return

    intents = discord.Intents.default()
    intents.guilds = True
    client = discord.Client(intents=intents)

    @client.event
    async def on_ready():
        print(f"Logged in as {client.user}")
        
        # 1. 대상 Guild (서버) 찾기
        # .env 에 GUILD_ID 가 없으면 첫 번째 서버 사용
        guild_id = os.environ.get("DISCORD_GUILD_ID")
        if guild_id:
            guild = client.get_guild(int(guild_id))
        else:
            guild = client.guilds[0] if client.guilds else None
            
        if not guild:
            print("Error: Guild not found.")
            await client.close()
            return

        print(f"Target Guild: {guild.name} ({guild.id})")

        # 2. 채널 생성 함수
        async def ensure_channel(name, type, **kwargs):
            existing = discord.utils.get(guild.channels, name=name)
            if existing:
                print(f"Channel '{name}' already exists.")
                return existing
            
            print(f"Creating channel '{name}'...")
            if type == discord.ChannelType.text:
                return await guild.create_text_channel(name, **kwargs)
            elif type == discord.ChannelType.forum:
                return await guild.create_forum_channel(name, **kwargs)
            return None

        # 3. 채널 구조 생성
        lobby = await ensure_channel("모부르지-로비", discord.ChannelType.text, topic="[Lobby] 대화 및 작업 요청")
        ops = await ensure_channel("mobruji-ops", discord.ChannelType.forum, topic="[Ops Center] 작업 격리 구역")
        status = await ensure_channel("mobruji-status", discord.ChannelType.text, topic="[Dashboard] 실시간 가동 현황판")

        print("\n--- Setup Complete ---")
        print(f"LOBBY_CHANNEL_ID={lobby.id}")
        print(f"FORUM_CHANNEL_ID={ops.id}")
        print(f"STATUS_CHANNEL_ID={status.id}")
        print("\n위 ID들을 .env 에 업데이트 하세요.")
        
        await client.close()

    await client.start(token)

if __name__ == "__main__":
    asyncio.run(setup_server())
