# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Keep AppWorld's process-wide simulated clock outside the trusted Runner."""
import multiprocessing
from contextlib import contextmanager
from pathlib import Path
import sys


@contextmanager
def task_evaluation_imports():
    """Official tasks import evaluation.py by basename; hide the Runner's namesake."""
    previous = sys.modules.pop('evaluation', None)
    original_path = list(sys.path)
    # ConfigMap mounts symlink individual files into a revision directory. Both
    # the import directory and resolved file directory must be excluded.
    excluded = {Path(__file__).parent.resolve(), Path(__file__).resolve().parent}
    if previous is not None and getattr(previous, '__file__', None):
        excluded.add(Path(previous.__file__).parent.resolve())
        excluded.add(Path(previous.__file__).resolve().parent)
    try:
        sys.path[:] = [p for p in original_path if Path(p).resolve() not in excluded]
        yield
    finally:
        sys.path[:] = original_path
        sys.modules.pop('evaluation', None)
        if previous is not None:
            sys.modules['evaluation'] = previous


class _OfficialWorld:
    def __init__(self, root, task, trial):
        from appworld import AppWorld, update_root
        update_root(root)
        self.root, self.task = Path(root), task
        self.world = AppWorld(task_id=task, experiment_name=trial,
                              remote_apis_url='http://127.0.0.1:9000')

    def initial(self):
        from evaluation import tree_fingerprint
        self.world.save()
        return {'databases': {'base': tree_fingerprint(self.root / 'data' / 'tasks' / self.task / 'dbs'),
                              'live': tree_fingerprint(self.world.output_db_home_path_on_disk)},
                'task': {'instruction': self.world.task.instruction,
                         'supervisor': dict(self.world.task.supervisor),
                         'datetime': self.world.task.datetime.isoformat()}}

    def evaluate(self):
        # MCP bypasses execute(); export remote changes before official grading.
        self.world.save()
        with task_evaluation_imports():
            return self.world.evaluate().to_dict()

    def close(self):
        self.world.close()


def _serve(connection, factory, root, task, trial):
    try:
        world = factory(root, task, trial)
        connection.send({'result': world.initial()})
        while True:
            operation = connection.recv()
            if operation == 'evaluate':
                connection.send({'result': world.evaluate()})
            elif operation == 'close':
                world.close()
                connection.send({'result': None})
                break
            else:
                raise ValueError('Unsupported AppWorld operation')
    except EOFError:
        pass
    except Exception as error:
        connection.send({'error': type(error).__name__ + ': ' + str(error)})
    finally:
        # Failure must preserve the remote world for inspection; never close/reset it here.
        connection.close()


class AppWorldProcess:
    def __init__(self, root, task, trial, timeout=180, factory=_OfficialWorld):
        context = multiprocessing.get_context('spawn')
        self.connection, child = context.Pipe()
        self.timeout = timeout
        self.process = context.Process(target=_serve, args=(child, factory, str(root), task, trial),
                                       daemon=True)
        self.process.start()
        child.close()
        try:
            self.initial = self._receive()
        except BaseException:
            self.stop()
            raise

    def _receive(self):
        if not self.connection.poll(self.timeout):
            self.stop()
            raise TimeoutError('AppWorld subprocess did not respond within its control deadline')
        try:
            result = self.connection.recv()
        except EOFError as error:
            raise RuntimeError('AppWorld subprocess exited without a response') from error
        if 'error' in result:
            raise RuntimeError('AppWorld subprocess: ' + result['error'])
        return result['result']

    def evaluate(self):
        self.connection.send('evaluate')
        return self._receive()

    def close(self):
        try:
            self.connection.send('close')
            self._receive()
        finally:
            self.stop()

    def stop(self):
        """Stop only the local controller; leave remote state untouched after failure."""
        if self.process.is_alive():
            self.process.terminate()
        self.process.join(timeout=5)
        if self.process.is_alive():
            self.process.kill()
            self.process.join(timeout=5)
        self.connection.close()
