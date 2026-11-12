# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Harbor agent interface delegating to the deployed Managed Agents Harness."""
from harbor.agents.base import BaseAgent


class ManagedSessionAgent(BaseAgent):
    def __init__(self, *args, driver, **kwargs):
        super().__init__(*args, **kwargs)
        self.driver = driver

    @staticmethod
    def name():
        return 'managed-session-api'

    def version(self):
        return '1'

    async def setup(self, environment):
        if self.driver.bridge.environment is not environment:
            raise ValueError('Terminal bridge does not belong to this exact task environment')
        await self.driver.bridge.setup()

    async def run(self, instruction, environment, context):
        if self.driver.bridge.environment is not environment:
            raise ValueError('Task environment changed after setup')
        try:
            row = await self.driver.run(instruction)
            context.n_input_tokens = row['accounting']['input_tokens']
            context.n_output_tokens = row['accounting']['output_tokens']
        finally:
            context.metadata = {'session_result': str(self.driver.output / 'session-result.json'),
                                'trial': self.driver.row['trial'], 'status': self.driver.row['status']}
